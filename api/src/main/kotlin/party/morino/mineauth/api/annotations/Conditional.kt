package party.morino.mineauth.api.annotations

/**
 * 条件付きリクエスト情報（[party.morino.mineauth.api.http.ConditionalRequest]）を受け取る
 * パラメータを定義するアノテーション
 *
 * ハンドラーは注入された`ConditionalRequest`から`If-None-Match`ヘッダーの値を参照し、
 * 高価なボディ生成の**前に**安価なETag（バージョンやタイムスタンプ）を比較することで、
 * キャッシュヒット時にボディ生成そのものをスキップできる（真の304短絡）。
 *
 * ```kotlin
 * @Get("/shops/{id}")
 * @Public
 * suspend fun getShop(
 *     @Path("id") id: String,
 *     @Conditional cond: ConditionalRequest
 * ): Either<HttpError, Response<ShopDto>> = either {
 *     val version = repo.versionOf(id) ?: raise(HttpError(HttpStatus.NOT_FOUND, "not found"))
 *     val etag = "\"shop-$id-$version\""
 *     if (cond.isNoneMatch(etag)) return@either Response.notModified(etag)
 *     Response.of(repo.loadFull(id).toDto(), etag = etag)
 * }
 * ```
 *
 * バインドできる型は`ConditionalRequest`のみ（non-nullable）。
 * `@Public`・`@Authenticated`どちらのエンドポイントでも使用できる。
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Conditional
