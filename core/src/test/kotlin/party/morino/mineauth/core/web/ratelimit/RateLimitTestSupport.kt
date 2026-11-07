package party.morino.mineauth.core.web.ratelimit

import party.morino.mineauth.core.file.data.RateLimitConfig
import java.util.logging.Logger

/**
 * テストモジュール向けの流量制限
 */
object RateLimitTestSupport {
    /**
     * 全てのリクエストを許可するインスタンスを作る
     * 流量制限を検証しないテストのKoinモジュールに登録し、同じクライアントからの連続リクエストを制限させない
     *
     * @return 無効な流量制限
     */
    fun disabled(): OAuthRateLimits =
        OAuthRateLimits(RateLimitConfig(enabled = false), emptyList(), Logger.getLogger("RateLimitTestSupport"))
}
