package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import party.morino.mineauth.core.web.router.auth.oauth.AuthorizeRouter.authorizeRouter
import party.morino.mineauth.core.web.router.auth.oauth.ProfileRouter.profileRouter
import party.morino.mineauth.core.web.router.auth.oauth.RevokeRouter.revokeRouter
import party.morino.mineauth.core.web.router.auth.oauth.TokenRouter.tokenRouter

object OAuthRouter: KoinComponent {
    fun Route.oauthRouter() {
        route("/oauth2") {
            authorizeRouter()
            tokenRouter()
            profileRouter()
            revokeRouter()
        }
    }

    // Ktorは並行リクエストを処理するためスレッドセーフなConcurrentHashMapを使用
    val authorizedData = ConcurrentHashMap<String, AuthorizedData>()

    // クリーンアップの最小間隔（60秒）: DoS対策としてリクエスト毎のO(n)走査を抑制
    private const val CLEANUP_INTERVAL_MS = 60_000L
    private val lastCleanupTime = AtomicLong(0)

    /**
     * 期限切れの認可コードを削除する（スロットリング付き）
     * RFC 6749 Section 4.1.2: 有効期限を過ぎた認可コードはリプレイ攻撃防止のため破棄する
     * 毎リクエストでのO(n)走査を避けるため、最小間隔を設けて実行頻度を制限する
     *
     * @param maxAgeMs 認可コードの最大有効期間（ミリ秒）
     */
    fun cleanupExpiredCodes(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val lastRun = lastCleanupTime.get()
        // 最小間隔以内の場合はスキップ（CASで競合を防止）
        if (now - lastRun < CLEANUP_INTERVAL_MS) return
        if (!lastCleanupTime.compareAndSet(lastRun, now)) return

        authorizedData.entries.removeIf { (_, data) ->
            now - data.authTime > maxAgeMs
        }
    }
}