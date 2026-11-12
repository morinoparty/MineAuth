package party.morino.mineauth.core.web.ratelimit

/**
 * リクエスト由来の値を、数える単位のキーに正規化する
 * 表記を変えるだけで別のキーとして数えられると制限を回避できるので、同じ対象は必ず同じキーにする
 */
internal object RateLimitKeys {
    // Minecraftのプレイヤー名より十分長い上限。超える値は実在しないので1つのキーにまとめ、長い文字列をキーとして保持しない
    private const val MAX_USERNAME_LENGTH = 64
    private const val OVERSIZED_USERNAME = "oversized-username"

    /**
     * ユーザー名をキーに正規化する
     * Minecraftのプレイヤー名は大文字小文字を区別しないので、小文字にそろえる
     *
     * @param username リクエストで提示されたユーザー名
     * @return 小文字のユーザー名。長すぎる場合は共通のキー
     */
    fun username(username: String): String =
        if (username.length > MAX_USERNAME_LENGTH) OVERSIZED_USERNAME else username.lowercase()

    /**
     * 接続元とユーザー名の組をキーに正規化する
     * どちらの値も任意の文字を含みうるので、区切り文字ではなく接続元の長さを先頭に付けて、異なる組が同じキーにならないようにする
     *
     * @param ip クライアントのキー
     * @param username リクエストで提示されたユーザー名
     * @return 組のキー
     */
    fun loginSource(ip: ClientIpKey, username: String): String =
        "${ip.value.length}:${ip.value}${username(username)}"
}
