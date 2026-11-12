package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header

/**
 * 認可エンドポイントの全レスポンスに付けるセキュリティヘッダー
 *
 * RFC 9700 Section 4.16: 認可画面はMinecraftのパスワードを受け取るので、iframeに埋め込まれると
 * クリックジャッキングで認証情報や同意を騙し取られる。エラー応答も対象にするため、ハンドラより前に設定する
 *
 * カスタムテンプレートがインラインや外部のリソースを使えるよう、CSPはframe-ancestorsだけを指定する
 */
val AuthorizeSecurityHeaders = createRouteScopedPlugin("AuthorizeSecurityHeaders") {
    onCall { call ->
        // CSP Level 2以降に対応するブラウザ向け
        call.response.header("Content-Security-Policy", "frame-ancestors 'none'")
        // CSPに対応しない古いブラウザ向け
        call.response.header("X-Frame-Options", "DENY")
        // ユーザーごとの認可画面や認可コード付きのリダイレクトをキャッシュさせない
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }
}
