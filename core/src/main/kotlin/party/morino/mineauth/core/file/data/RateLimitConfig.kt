package party.morino.mineauth.core.file.data

import kotlinx.serialization.Serializable

/**
 * OAuthエンドポイントの流量制限設定
 *
 * カウンターはサーバーごとのメモリ上にあり、再起動と /mineauth reload でリセットされる。
 * 回数の設定に0以下を指定すると、その制限だけを無効にする
 */
@Serializable
data class RateLimitConfig(
    // falseの場合は流量制限と失敗時のバックオフを全て無効にする
    // Argon2の同時実行数の制限（Argon2Gate）はメモリの保護なので、この設定では無効にならない
    val enabled: Boolean = true,

    // X-Forwarded-Forを読んでよいプロキシのIPアドレスまたはCIDR（解釈できない値は警告して無視する）
    // 既定でプライベートアドレスを含めているのは、ローカルのリバースプロキシの配下で
    // 全クライアントがプロキシのアドレスを共有し、1人の失敗で全員が制限されるのを防ぐため
    val trustedProxies: List<String> = listOf(
        "127.0.0.0/8",
        "::1/128",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "fc00::/7"
    ),

    // Publicクライアントによるトークンエンドポイントへの1分あたりのリクエスト数（クライアントIPごと）
    // 誰でもclient_idを名乗れるので、認証に成功した後にIPごとに数える
    val tokenRequestsPerMinutePerIp: Int = 120,

    // Confidentialクライアントによるトークンエンドポイントへの1分あたりのリクエスト数（client_idごと）
    // シークレットの検証に成功した後に数え、IPごとの制限は使わない
    // アクセストークンの有効期限は300秒なので、同時に利用する利用者数の1/5程度が目安になる
    val tokenRequestsPerMinutePerClient: Int = 600,

    // パスワードログインの1分あたりの試行回数（クライアントIPごと）
    val loginAttemptsPerMinutePerIp: Int = 30
)
