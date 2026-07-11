package party.morino.mineauth.api.http

/**
 * `Cache-Control`レスポンスヘッダーを型安全に表現する値オブジェクト
 *
 * 読み取り専用エンドポイントのレスポンスにキャッシュ指示を付与し、クライアントや
 * リバースプロキシ・CDNが繰り返しのリクエストをオフロードできるようにする。
 *
 * ```kotlin
 * Response.of(dto, cacheControl = CacheControl.maxAge(60))          // private, max-age=60
 * Response.of(dto, cacheControl = CacheControl.maxAge(300, PUBLIC)) // public, max-age=300
 * ```
 *
 * @property visibility キャッシュの可視性（`public` / `private`）
 * @property maxAgeSeconds `max-age`（秒）。nullなら付与しない
 * @property noStore `no-store`（一切キャッシュしない）
 * @property noCache `no-cache`（再検証を要求する）
 * @property mustRevalidate `must-revalidate`
 */
data class CacheControl(
    val visibility: Visibility = Visibility.PRIVATE,
    val maxAgeSeconds: Long? = null,
    val noStore: Boolean = false,
    val noCache: Boolean = false,
    val mustRevalidate: Boolean = false
) {
    /** キャッシュの可視性 */
    enum class Visibility { PUBLIC, PRIVATE }

    /**
     * `Cache-Control`ヘッダーの値へ変換する（例: `private, max-age=60`）
     */
    fun toHeaderValue(): String = buildList {
        // no-storeが指定されている場合は他の指示と併記せず単独で返す
        if (noStore) {
            add("no-store")
            return@buildList
        }
        add(if (visibility == Visibility.PUBLIC) "public" else "private")
        if (noCache) add("no-cache")
        maxAgeSeconds?.let { add("max-age=$it") }
        if (mustRevalidate) add("must-revalidate")
    }.joinToString(", ")

    companion object {
        /**
         * `max-age`を指定したキャッシュ指示を生成する
         *
         * @param seconds キャッシュ有効秒数
         * @param visibility 可視性（既定は`private`）
         */
        fun maxAge(seconds: Long, visibility: Visibility = Visibility.PRIVATE): CacheControl =
            CacheControl(visibility = visibility, maxAgeSeconds = seconds)

        /** 一切キャッシュしない指示（`no-store`） */
        val NoStore: CacheControl = CacheControl(noStore = true)
    }
}
