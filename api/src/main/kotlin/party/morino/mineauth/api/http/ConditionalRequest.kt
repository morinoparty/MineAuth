package party.morino.mineauth.api.http

/**
 * 条件付きリクエスト（HTTP conditional request）の情報を提供する読み取り専用オブジェクト
 *
 * `@Conditional`パラメータとしてハンドラーに注入される。ハンドラーは高価なボディ生成の
 * **前に**安価なETagを計算し、[isNoneMatch]で`If-None-Match`と照合することで、
 * キャッシュヒット時に[Response.notModified]を返してボディ生成・直列化の双方をスキップできる。
 *
 * v1では`If-None-Match`のみを扱う（`If-Modified-Since`は未対応）。
 *
 * @property ifNoneMatch リクエストの`If-None-Match`ヘッダーから抽出したETag値のリスト
 */
class ConditionalRequest internal constructor(
    private val ifNoneMatch: List<String>
) {
    /**
     * 指定したETagが`If-None-Match`に一致するか判定する（RFC 7232準拠）
     *
     * - `*` はあらゆるETagに一致する
     * - 弱いバリデータ（`W/"..."`）は弱い比較で照合する
     *   （条件付きGETでは弱い比較が許容される）
     *
     * @param etag 比較対象のETag（引用符付きの完全な形式、例: `"shop-1-42"` や `W/"v3"`）
     * @return 一致すれば true（呼び出し側は304を返すべき）
     */
    fun isNoneMatch(etag: String): Boolean {
        if (ifNoneMatch.isEmpty()) return false
        if (ifNoneMatch.any { it == "*" }) return true
        val target = weakUnwrap(etag)
        return ifNoneMatch.any { weakUnwrap(it) == target }
    }

    /** `W/`接頭辞を除去して弱い比較用に正規化する */
    private fun weakUnwrap(value: String): String =
        value.removePrefix("W/").trim()

    companion object {
        /**
         * `If-None-Match`ヘッダー値（複数ヘッダー・カンマ区切りの両方）をETagのリストへ解析する
         *
         * @param headerValues `getAll("If-None-Match")`等で得た生のヘッダー値
         * @return 空白を除去したETagのリスト
         */
        fun fromHeaderValues(headerValues: List<String>): ConditionalRequest =
            ConditionalRequest(
                headerValues
                    .flatMap { it.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            )
    }
}
