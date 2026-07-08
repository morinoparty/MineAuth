package party.morino.mineauth.core.plugin.route

import io.ktor.server.application.ApplicationCall
import party.morino.mineauth.api.auth.Principal

/**
 * 1リクエストの処理に必要なコンテキスト情報
 * ディスパッチャで解決された認証主体とパスパラメータを保持する
 *
 * @property call KtorのApplicationCall
 * @property principal 認証済みPrincipal（公開エンドポイントで未認証の場合null）
 * @property pathParams ディスパッチャのパスマッチングで抽出されたパスパラメータ
 */
data class RequestContext(
    val call: ApplicationCall,
    val principal: Principal?,
    val pathParams: Map<String, String>
)
