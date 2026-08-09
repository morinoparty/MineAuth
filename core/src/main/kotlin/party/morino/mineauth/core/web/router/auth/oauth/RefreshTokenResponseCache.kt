package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.left
import kotlinx.coroutines.CompletableDeferred
import party.morino.mineauth.core.web.components.auth.TokenData
import java.util.concurrent.ConcurrentHashMap

/**
 * リフレッシュトークンローテーションのレスポンスキャッシュ
 *
 * RFC 9700 (OAuth 2.0 Security BCP) Section 4.14.2 に基づく reuse grace period の実装。
 * 旧リフレッシュトークンのtokenId（jti）をキーに発行済みトークンレスポンスをgrace期間だけ保持し、
 * 同一リフレッシュトークンでの並行・直後の再リクエストには同じレスポンスを返す（べき等化）。
 *
 * これにより、ブラウザの複数タブや並列fetchによる同時リフレッシュが全員同じ新トークンを
 * 受け取れるようになり、ローテーション競合による invalid_grant を解消する。
 *
 * grace期間経過後の再利用は従来どおり拒否されるため、
 * 盗難検知としてのトークンローテーションの意味は維持される。
 *
 * @param gracePeriodMillis grace期間（ミリ秒）。この期間内の再利用に同一レスポンスを返す
 * @param clock 現在時刻の取得関数（テストで時間を制御するために注入可能）
 */
class RefreshTokenResponseCache(
    private val gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        // RFC 9700 Section 4.14.2 が言及する限定的な再利用許容のためのgrace期間
        // 並行リフレッシュ競合の解消には十分で、盗難トークンの悪用窓を最小限に抑える長さ
        const val DEFAULT_GRACE_PERIOD_MILLIS = 60_000L
    }

    /**
     * キャッシュエントリ
     * deferredにより「発行中」の状態を表現でき、並行リクエストは同じ発行結果を待機できる
     */
    private class Entry(
        val deferred: CompletableDeferred<Either<TokenRotationFailure, TokenData>>,
        val createdAt: Long
    )

    // 旧tokenId（jti） → 発行結果のマップ
    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * tokenIdに対応する発行済みレスポンスを取得するか、なければ発行処理を実行する
     *
     * 同一tokenIdへの並行呼び出しはputIfAbsentで単一の発行処理に集約され（シングルフライト）、
     * 敗者は勝者の発行結果を待機して同じレスポンスを受け取る。
     * 発行成功時はgrace期間だけ結果を保持し、期間内の後続リクエストにも同じレスポンスを返す。
     * 発行失敗時は結果をキャッシュせず、次のリクエストで再試行できるようにする。
     *
     * @param tokenId 旧リフレッシュトークンのJWT ID（署名検証済みのjti claim）
     * @param issue トークン発行処理（tokenIdごとに最初の1リクエストのみ実行される）
     * @return 発行結果（成功時はTokenData、失敗時はTokenRotationFailure）
     */
    suspend fun getOrIssue(
        tokenId: String,
        issue: suspend () -> Either<TokenRotationFailure, TokenData>
    ): Either<TokenRotationFailure, TokenData> {
        // リクエスト契機でgrace期間を過ぎたエントリを削除（遅延クリーンアップ）
        cleanupExpired()

        val newEntry = Entry(CompletableDeferred(), clock())
        val existing = cache.putIfAbsent(tokenId, newEntry)
        if (existing != null) {
            // grace期間内の再利用: 発行済み（または発行中）のレスポンスを共有する
            return existing.deferred.await()
        }

        // 勝者として発行処理を実行する
        val result = try {
            issue()
        } catch (e: Throwable) {
            // 発行中の例外（リクエストのキャンセル含む）ではエントリを削除して再試行可能にし、
            // 待機中の並行リクエストはserver_errorで解放する
            // （例外をそのままdeferredに渡すと待機側のリクエストまで巻き込んでキャンセルされるため）
            cache.remove(tokenId)
            newEntry.deferred.complete(
                TokenRotationFailure(OAuthErrorCode.SERVER_ERROR, "Failed to rotate refresh token").left()
            )
            throw e
        }

        if (result.isLeft()) {
            // 失敗はキャッシュしない: エントリを削除してから完了させ、
            // 待機明けに再試行するリクエストが死んだエントリに合流しないようにする
            cache.remove(tokenId)
        }
        newEntry.deferred.complete(result)
        return result
    }

    /**
     * grace期間を過ぎた完了済みエントリを削除する
     * 発行中（未完了）のエントリは重複発行を防ぐため削除しない
     */
    private fun cleanupExpired() {
        val now = clock()
        cache.entries.removeIf { (_, entry) ->
            entry.deferred.isCompleted && now - entry.createdAt > gracePeriodMillis
        }
    }
}
