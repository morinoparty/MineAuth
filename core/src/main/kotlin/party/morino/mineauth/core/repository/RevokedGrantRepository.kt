package party.morino.mineauth.core.repository

import arrow.core.Either
import arrow.core.getOrElse
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import party.morino.mineauth.core.database.RevokedGrants
import party.morino.mineauth.core.database.RevokedTokens
import party.morino.mineauth.core.model.TokenLifetimes
import party.morino.mineauth.core.web.telemetry.withDatabaseSpan
import java.sql.SQLException
import java.time.LocalDateTime

/**
 * 失効グラントリポジトリ
 * RFC 6749 Section 4.1.2 と RFC 9700 Section 4.14.2 が求める、グラント単位の失効を管理する
 *
 * 例外をトランザクションの外で捕捉するのは、Exposedの再試行を活かしつつ、
 * コミットの失敗も失敗として扱うためである（失効を記録できていないのに成功と報告しない）
 */
object RevokedGrantRepository {

    /**
     * グラントを失効させる
     *
     * レコードの削除日時はサーバーが計算し、提示されたトークンの有効期限からは求めない。
     * ローテーションでグラントは延長されるため、同じグラントの最新のトークンは提示されたトークンより長く生きている場合があり、
     * 進行中のローテーションが書き込みの直後に新しいトークンを発行することもある。早く削除すると失効済みのトークンが有効に戻ってしまう
     *
     * 既に失効済みの場合は何もせず成功する。複数サーバーの競合でも安全なように、確認と追加を分けずinsertIgnoreを使う
     *
     * @param grantId グラントID（grant_id claim）
     * @param clientId クライアントID
     * @return 成功時はUnit、失敗時はエラー
     */
    suspend fun revoke(grantId: String, clientId: String): Either<RevokedGrantError, Unit> =
        catchingDatabaseError {
            withDatabaseSpan("revoked_grants", "insert") {
                newSuspendedTransaction {
                    insertRevocation(grantId, clientId)
                }
            }
        }

    /**
     * リフレッシュトークンの再利用を根拠にグラントを失効させる
     *
     * RevokedTokenRepository.isRevokedは読み取りに失敗してもtrueを返す（fail closed）ため、再利用の証拠にはならない。
     * それを根拠に失効させるとデータベースの一時的な障害で正常なセッションまで終わらせてしまうので、
     * トークンの失効記録が実在することを同じトランザクションで確認できた場合だけ失効させる
     * （呼び出し側が記録を読み取った後にクリーンアップで消えた場合も失効させない）
     *
     * @param grantId グラントID（grant_id claim）
     * @param clientId クライアントID
     * @param tokenId 再利用されたリフレッシュトークンのJWT ID（jti claim）
     * @return 失効記録を確認して失効させた場合はtrue、失効記録がなければfalse、データベース操作の失敗時はエラー
     */
    suspend fun revokeForReusedToken(
        grantId: String,
        clientId: String,
        tokenId: String
    ): Either<RevokedGrantError, Boolean> =
        catchingDatabaseError {
            withDatabaseSpan("revoked_grants", "insert") {
                newSuspendedTransaction {
                    val tokenRevoked = RevokedTokens.selectAll()
                        .where { RevokedTokens.tokenId eq tokenId }
                        .firstOrNull() != null
                    if (tokenRevoked) insertRevocation(grantId, clientId)
                    tokenRevoked
                }
            }
        }

    /**
     * グラントが失効済みかチェックする
     *
     * @param grantId グラントID（grant_id claim）
     * @return 失効済み、または失効していないことを確認できない場合true
     */
    suspend fun isRevoked(grantId: String): Boolean =
        // エラー時は安全側に倒して失効済みとして扱う
        checkRevoked(grantId).getOrElse { true }

    /**
     * グラントの失効状態を読み取る
     *
     * isRevokedと違い、読み取りの失敗を失効済みとは扱わずエラーとして返す。
     * refresh_tokenグラントは、障害中にinvalid_grantを返してクライアントに有効なトークンを捨てさせないよう、
     * 失効済みと読み取りの失敗を区別するためにこれを使う
     *
     * @param grantId グラントID（grant_id claim）
     * @return 失効済みならtrue、失効していなければfalse、データベース操作の失敗時はエラー
     */
    suspend fun checkRevoked(grantId: String): Either<RevokedGrantError, Boolean> =
        catchingDatabaseError {
            withDatabaseSpan("revoked_grants", "select") {
                newSuspendedTransaction {
                    RevokedGrants.selectAll()
                        .where { RevokedGrants.grantId eq grantId }
                        .firstOrNull() != null
                }
            }
        }

    /**
     * 削除日時を過ぎた失効グラントレコードをクリーンアップする
     * 削除日時にマージンが含まれているため、削除の時点で同じグラントのトークンはどの検証も通らない
     *
     * @return 削除されたレコード数
     * @throws SQLException データベース操作に失敗した場合（定期実行の側で記録する）
     */
    suspend fun cleanupExpired(): Int = withDatabaseSpan("revoked_grants", "delete") {
        newSuspendedTransaction {
            RevokedGrants.deleteWhere {
                expiresAt less LocalDateTime.now()
            }
        }
    }

    /**
     * 失効レコードを追加する（呼び出し側のトランザクション内で実行する）
     * 新しいトークンが発行されうる期間を覆うよう、リフレッシュトークンの有効期間にマージンを加えた日時まで保持する
     *
     * @param grantId グラントID
     * @param clientId クライアントID
     */
    private fun insertRevocation(grantId: String, clientId: String) {
        RevokedGrants.insertIgnore {
            it[RevokedGrants.grantId] = grantId
            it[RevokedGrants.clientId] = clientId
            it[RevokedGrants.expiresAt] = LocalDateTime.now()
                .plus(TokenLifetimes.REFRESH_TOKEN)
                .plus(TokenLifetimes.REVOCATION_RECORD_MARGIN)
        }
    }

    /**
     * データベース操作の失敗だけをエラーへ変換する
     * それ以外の例外（コルーチンのキャンセルを含む）は成功に見せかけないようそのまま送出する
     *
     * @param block データベース操作
     * @return 操作の結果、またはエラー
     */
    private inline fun <T> catchingDatabaseError(block: () -> T): Either<RevokedGrantError, T> =
        Either.catchOrThrow<SQLException, T>(block)
            .mapLeft { RevokedGrantError.DatabaseError(it.message ?: "Unknown error") }
}
