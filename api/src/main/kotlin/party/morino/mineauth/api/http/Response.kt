package party.morino.mineauth.api.http

/**
 * レスポンスヘッダー（`Cache-Control` / `ETag`）や条件付き304を制御するためのハンドラー戻り値ラッパー
 *
 * ハンドラーは生の`@Serializable` DTOをそのまま返すことも、この`Response<T>`でラップして
 * キャッシュ指示やETagを付与することもできる。`Either<HttpError, Response<T>>`のように
 * Arrowの`Either`と組み合わせても使える（`out T`により[NotModified]も同じ式で返せる）。
 *
 * ```kotlin
 * // 単純なキャッシュ付きレスポンス
 * @Get("/config") @Public
 * suspend fun config(): Response<ConfigDto> =
 *     Response.of(loadConfig(), etag = configEtag(), cacheControl = CacheControl.maxAge(60))
 *
 * // 条件付き304（安価なETagで高価なボディ生成をスキップ）
 * @Get("/shops/{id}") @Public
 * suspend fun getShop(@Path("id") id: String, @Conditional cond: ConditionalRequest): Either<HttpError, Response<ShopDto>> = either {
 *     val version = repo.versionOf(id) ?: raise(HttpError(HttpStatus.NOT_FOUND, "not found"))
 *     val etag = "\"shop-$id-$version\""
 *     if (cond.isNoneMatch(etag)) return@either Response.notModified(etag)
 *     Response.of(repo.loadFull(id).toDto(), etag = etag, cacheControl = CacheControl.maxAge(60))
 * }
 * ```
 *
 * ボディは常に**具体化された値**として保持する（`() -> T`のようなラムダは保持しない）。
 * 利用側クラスローダのラムダをMineAuthが呼び出すとクラスローダ分裂（issue #378と同種）を
 * 招くため、遅延ボディはv1では意図的に提供しない。
 *
 * @property etag レスポンスの`ETag`ヘッダー値（引用符付きの完全な形式、nullなら付与しない）
 * @property cacheControl `Cache-Control`ヘッダー（nullなら付与しない）
 * @property headers 追加のレスポンスヘッダー
 */
sealed interface Response<out T> {
    val etag: String?
    val cacheControl: CacheControl?
    val headers: Map<String, String>

    /**
     * ボディを伴う成功レスポンス
     *
     * @property status HTTPステータス（既定は200 OK）
     * @property body 直列化対象のボディ（具体化された値）
     */
    class Ok<out T> internal constructor(
        val status: HttpStatus,
        val body: T,
        override val etag: String?,
        override val cacheControl: CacheControl?,
        override val headers: Map<String, String>
    ) : Response<T>

    /**
     * ボディを持たない304 Not Modifiedレスポンス
     *
     * `out T`により`Response<Nothing>`として任意の`Response<T>`の位置に代入できる。
     */
    class NotModified internal constructor(
        override val etag: String?,
        override val cacheControl: CacheControl?,
        override val headers: Map<String, String>
    ) : Response<Nothing>

    companion object {
        /**
         * ボディ付きの成功レスポンスを生成する
         *
         * @param body 直列化対象のボディ
         * @param etag `ETag`（任意、引用符付きの完全な形式）
         * @param cacheControl `Cache-Control`（任意）
         * @param status HTTPステータス（既定は200 OK）
         * @param headers 追加ヘッダー（任意）
         */
        fun <T> of(
            body: T,
            etag: String? = null,
            cacheControl: CacheControl? = null,
            status: HttpStatus = HttpStatus.OK,
            headers: Map<String, String> = emptyMap()
        ): Response<T> = Ok(status, body, etag, cacheControl, headers)

        /**
         * 304 Not Modifiedレスポンスを生成する
         *
         * ハンドラーが`If-None-Match`との一致を検出したときに返す。ボディ生成・直列化は行われない。
         *
         * @param etag 一致したETag（304レスポンスにも付与される）
         * @param cacheControl `Cache-Control`（任意）
         * @param headers 追加ヘッダー（任意）
         */
        fun notModified(
            etag: String,
            cacheControl: CacheControl? = null,
            headers: Map<String, String> = emptyMap()
        ): Response<Nothing> = NotModified(etag, cacheControl, headers)
    }
}
