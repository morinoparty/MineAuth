package party.morino.mineauth.core.repository

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.recover
import arrow.core.right
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import party.morino.mineauth.core.database.RevokedTokens
import party.morino.mineauth.core.model.TokenLifetimes
import party.morino.mineauth.core.web.telemetry.withDatabaseSpan
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * トークン種別
 */
enum class TokenType(val value: String) {
    ACCESS_TOKEN("access_token"),
    REFRESH_TOKEN("refresh_token");

    companion object {
        fun fromValue(value: String): TokenType? = entries.find { it.value == value }
        fun fromHint(hint: String?): TokenType? = when (hint) {
            "access_token" -> ACCESS_TOKEN
            "refresh_token" -> REFRESH_TOKEN
            else -> null
        }
    }
}

/**
 * 失効トークン操作エラー
 */
sealed class RevokedTokenError {
    // 失効記録が既に存在した（revokeIfAbsentだけが返す。revokeは成功として扱う）
    data object AlreadyRevoked : RevokedTokenError()
    data class DatabaseError(val message: String) : RevokedTokenError()
}

/**
 * 失効トークンリポジトリ
 * RFC 7009 Token Revocationに基づくトークン失効管理
 *
 * JWTトークンはステートレスなため、失効したトークンをブラックリストとして管理する
 *
 * Either を返す操作は例外をトランザクションの外で捕捉する。Exposedの再試行を活かしつつ、
 * コミットの失敗も失敗として扱うためである（RevokedGrantRepositoryと同じ方針）
 */
object RevokedTokenRepository {

    /**
     * トークンを失効させる
     *
     * RFC 7009: 既に失効済みでも成功として扱う。確認と追加を1文で行うため、同じトークンの並行した失効も成功する
     * 失効した時刻には現在時刻を記録する（revokeIfAbsentを参照）
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @param tokenType トークン種別
     * @param clientId クライアントID
     * @param expiresAt トークンの有効期限
     * @return 成功時はUnit、データベース操作の失敗時はDatabaseError
     */
    suspend fun revoke(
        tokenId: String,
        tokenType: TokenType,
        clientId: String,
        expiresAt: LocalDateTime
    ): Either<RevokedTokenError, Unit> =
        revokeIfAbsent(tokenId, tokenType, clientId, expiresAt).recover { error ->
            // 既に失効済みなら成功、それ以外の失敗はそのまま返す
            if (error !is RevokedTokenError.AlreadyRevoked) raise(error)
        }

    /**
     * 失効記録がまだない場合だけトークンを失効させ、この呼び出しが記録を作成したかを返す
     *
     * 確認と追加を分けずinsertIgnoreの1文で行うので、複数サーバーが同じデータベースを共有していても
     * 記録を作成できる呼び出しは1つだけである。リフレッシュトークンのローテーションはこれを使い、
     * 並行したローテーション（他のサーバーを含む）や/oauth2/revokeに先を越されたトークンから新しいトークンを発行しない
     *
     * 失効した時刻は、grace期間の判定に使うエポックミリ秒（revoked_at_ms）と、運用者が読むためのサーバーのタイムゾーンの日時（revoked_at）の
     * 両方を同じ文で記録する。grace期間の判定は別のサーバーが読み取ることもあるため、
     * データベースやドライバーのタイムゾーン変換と夏時間の切り替わりの影響を受けない数値を判定に使う
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @param tokenType トークン種別
     * @param clientId クライアントID
     * @param expiresAt トークンの有効期限（サーバーのタイムゾーン）
     * @param revokedAt 失効した時刻（ローテーションはgrace期間の判定に使う時計の時刻を渡す）
     * @return 記録を作成した場合はUnit、既に記録があった場合はAlreadyRevoked、データベース操作の失敗時はDatabaseError
     */
    suspend fun revokeIfAbsent(
        tokenId: String,
        tokenType: TokenType,
        clientId: String,
        expiresAt: LocalDateTime,
        revokedAt: Instant = Instant.now()
    ): Either<RevokedTokenError, Unit> =
        catchingDatabaseError {
            withDatabaseSpan("revoked_tokens", "insert") {
                newSuspendedTransaction {
                    RevokedTokens.insertIgnore {
                        it[RevokedTokens.tokenId] = tokenId
                        it[RevokedTokens.tokenType] = tokenType.value
                        it[RevokedTokens.clientId] = clientId
                        // 判定に使うのはエポックミリ秒のほうで、日時はテーブルを読む運用者のためにサーバーのタイムゾーンで残す
                        it[RevokedTokens.revokedAt] = LocalDateTime.ofInstant(revokedAt, ZoneId.systemDefault())
                        it[RevokedTokens.revokedAtEpochMillis] = revokedAt.toEpochMilli()
                        it[RevokedTokens.expiresAt] = expiresAt
                    }.insertedCount
                }
            }
        }.flatMap { insertedCount ->
            // 主キーの衝突で無視された場合は0件になる
            if (insertedCount > 0) Unit.right() else RevokedTokenError.AlreadyRevoked.left()
        }

    /**
     * トークンが失効済みかチェックする
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @return 失効済みの場合true
     */
    suspend fun isRevoked(tokenId: String): Boolean = withDatabaseSpan("revoked_tokens", "select") {
        newSuspendedTransaction {
            try {
                RevokedTokens.selectAll()
                    .where { RevokedTokens.tokenId eq tokenId }
                    .firstOrNull() != null
            } catch (e: Exception) {
                // エラー時は安全側に倒して失効済みとして扱う
                true
            }
        }
    }

    /**
     * トークンの失効した時刻を読み取る
     *
     * isRevokedと違い、読み取りの失敗を失効済みとは扱わずエラーとして返す。
     * refresh_tokenグラントは、障害中にinvalid_grantを返してクライアントに有効なトークンを捨てさせないよう、
     * 失効済みと読み取りの失敗を区別するためにこれを使う
     *
     * 時刻はエポックミリ秒（revoked_at_ms）だけから読み、revoked_atは使わない。
     * revoked_atはサーバーのタイムゾーンの日時で、データベースによってはタイムゾーン変換で値がずれるためである。
     * revoked_at_msのない行（カラムの追加前や、追加前のバージョンが書いた行）はUnrecordedとして返す
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @return 失効記録の有無と失効した時刻、データベース操作の失敗時はDatabaseError
     */
    suspend fun findRevokedAt(tokenId: String): Either<RevokedTokenError, TokenRevocationTime> =
        catchingDatabaseError {
            withDatabaseSpan("revoked_tokens", "select") {
                newSuspendedTransaction {
                    val row = RevokedTokens.select(RevokedTokens.revokedAtEpochMillis)
                        .where { RevokedTokens.tokenId eq tokenId }
                        .firstOrNull()
                    val epochMillis = row?.get(RevokedTokens.revokedAtEpochMillis)
                    when {
                        row == null -> TokenRevocationTime.NotRevoked
                        // 記録はあっても時刻がない行は、0などの値で埋めず別の状態として返す
                        epochMillis == null -> TokenRevocationTime.Unrecorded
                        else -> TokenRevocationTime.Recorded(Instant.ofEpochMilli(epochMillis))
                    }
                }
            }
        }

    /**
     * 期限切れの失効トークンレコードをクリーンアップする
     * 定期的に実行することでテーブルサイズを抑制する
     *
     * expires_atはトークン自身の有効期限である。Bearer検証は有効期限後も短い猶予の間トークンを受け付けるため
     * （TokenVerifiers）、期限の直後に削除すると失効済みのトークンが有効に戻ってしまう。マージンを過ぎたものだけを削除する
     *
     * @return 削除されたレコード数
     * @throws java.sql.SQLException データベース操作に失敗した場合（定期実行の側で記録する）
     */
    suspend fun cleanupExpired(): Int = newSuspendedTransaction {
        RevokedTokens.deleteWhere {
            expiresAt less LocalDateTime.now().minus(TokenLifetimes.REVOCATION_RECORD_MARGIN)
        }
    }

    /**
     * トークンが失効済みかチェックする（ブロッキング版）
     * JWT検証コールバック用のヘルパーメソッド
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @return 失効済みの場合true
     */
    fun isRevokedBlocking(tokenId: String): Boolean = runBlocking {
        isRevoked(tokenId)
    }

    /**
     * データベース操作の失敗だけをエラーへ変換する
     * それ以外の例外（コルーチンのキャンセルを含む）は成功に見せかけないようそのまま送出する
     *
     * @param block データベース操作
     * @return 操作の結果、またはエラー
     */
    private inline fun <T> catchingDatabaseError(block: () -> T): Either<RevokedTokenError, T> =
        Either.catchOrThrow<SQLException, T>(block)
            .mapLeft { RevokedTokenError.DatabaseError(it.message ?: "Unknown error") }
}
