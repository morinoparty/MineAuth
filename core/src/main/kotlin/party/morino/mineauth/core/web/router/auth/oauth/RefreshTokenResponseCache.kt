package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
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
 * このクラスは応答を再現するだけで、失効状態も盗難も判定しない。
 * 失効済みグラントの拒否はキャッシュの照会より先に、grace期間外の再利用の検知とグラントの失効は
 * 発行処理の中で、いずれも呼び出し側（TokenRouter）が行う。キャッシュから返す応答だけに必要な確認も、
 * 呼び出し側が渡す関数（getOrIssueのverifyCached）で行う。
 * grace期間の長さと時計はこのクラスが持ち、キャッシュ未ヒット時の再提示の判定（isWithinGracePeriod）にも使う。
 * 明示的に失効されたトークンの応答を返し続けないよう、失効の際はinvalidateでエントリを取り除く。
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
     * verifyCachedは、他のリクエストが発行した応答（発行中のものに合流した場合を含む）を返す前にだけ適用する。
     * この呼び出しが発行した応答には適用しない。発行処理は旧トークンを使用済みにしているので、その後に失敗させると
     * クライアントは後継のトークンを受け取れないまま旧トークンを失うためである。
     * 確認の失敗はこの呼び出しの結果にだけ反映し、キャッシュ済みの応答は残す
     *
     * @param tokenId 旧リフレッシュトークンのJWT ID（署名検証済みのjti claim）
     * @param verifyCached キャッシュから返す応答の確認（既定では何もしない）
     * @param issue トークン発行処理（tokenIdごとに最初の1リクエストのみ実行される）
     * @return 発行結果（成功時はTokenData、失敗時はTokenRotationFailure）
     */
    suspend fun getOrIssue(
        tokenId: String,
        verifyCached: suspend (TokenData) -> Either<TokenRotationFailure, TokenData> = { it.right() },
        issue: suspend () -> Either<TokenRotationFailure, TokenData>
    ): Either<TokenRotationFailure, TokenData> {
        // リクエスト契機でgrace期間を過ぎたエントリを削除（遅延クリーンアップ）
        cleanupExpired()

        val newEntry = Entry(CompletableDeferred(), clock())
        val existing = cache.putIfAbsent(tokenId, newEntry)
        if (existing != null) {
            // grace期間内の再利用: 発行済み（または発行中）のレスポンスを、呼び出し側の確認を通してから共有する
            return existing.deferred.await().flatMap { verifyCached(it) }
        }

        // 勝者として発行処理を実行する
        val result = try {
            issue()
        } catch (e: Throwable) {
            // 発行中の例外（リクエストのキャンセル含む）ではエントリを削除して再試行可能にし、
            // 待機中の並行リクエストはserver_errorで解放する
            // （例外をそのままdeferredに渡すと待機側のリクエストまで巻き込んでキャンセルされるため）
            // invalidateの後に別のリクエストが登録したエントリを消さないよう、自分のエントリだけを削除する
            cache.remove(tokenId, newEntry)
            newEntry.deferred.complete(
                TokenRotationFailure(OAuthErrorCode.SERVER_ERROR, "Failed to rotate refresh token").left()
            )
            throw e
        }

        if (result.isLeft()) {
            // 失敗はキャッシュしない: エントリを削除してから完了させ、
            // 待機明けに再試行するリクエストが死んだエントリに合流しないようにする
            // （上と同じ理由で自分のエントリだけを削除する）
            cache.remove(tokenId, newEntry)
        }
        newEntry.deferred.complete(result)
        return result
    }

    /**
     * tokenIdのエントリを削除し、以後の再提示にキャッシュ済みの応答を返さないようにする
     *
     * 発行中のエントリも削除する。発行中のリクエストとその待機者には発行結果が渡るが、
     * 削除の後に届いたリクエストはキャッシュ未ヒットとなり、発行処理の中の失効チェックで拒否される
     *
     * @param tokenId 失効させたリフレッシュトークンのJWT ID
     */
    fun invalidate(tokenId: String) {
        cache.remove(tokenId)
    }

    /**
     * grace期間の判定に使う時計の現在時刻を返す
     *
     * ローテーションが書き込む失効記録の日時にもこの時刻を使い、
     * 再提示の際の経過時間の判定（isWithinGracePeriod）と同じ時計で測るようにする
     *
     * @return 現在時刻（エポックミリ秒）
     */
    fun now(): Long = clock()

    /**
     * 指定した時刻からの経過がgrace期間内かを判定する
     *
     * キャッシュの応答を持たないサーバー（再起動の後や、ローテーションしたのとは別のサーバー）が、
     * 直前のローテーションへの再試行とgrace期間を過ぎた再利用を見分けるために使う。
     * 境界はキャッシュのエントリの保持期間と一致させる
     *
     * 起点が未来にある場合も、その差がgrace期間以内ならサーバー間の時計のずれとみなしてgrace期間内とする。
     * それより先の時刻は期間外とする。読み違えた時刻やずれた時計の記録が、再利用の検知を何時間も止めないようにするためである
     *
     * @param sinceMillis 起点の時刻（エポックミリ秒）
     * @return grace期間内ならtrue
     */
    fun isWithinGracePeriod(sinceMillis: Long): Boolean = clock() - sinceMillis in -gracePeriodMillis..gracePeriodMillis

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
