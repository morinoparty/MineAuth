package party.morino.mineauth.core.web.page

import kotlinx.serialization.json.Json
import party.morino.mineauth.core.web.router.auth.data.AuthorizePageModel

/**
 * 認可画面のHTMLを組み立てる
 *
 * フロントエンド（core/src/main/frontend）のビルド成果物はJS/CSSをインライン化した1枚のHTMLで、
 * ビューモデルを受け取る `<script type="application/json">` のプレースホルダを持つ。
 * ここではそのプレースホルダをビューモデルのJSONで置き換えるだけで、描画はReactに任せる
 */
object AuthorizePageRenderer {
    // Gradleのフロントエンドビルドタスクがクラスパスに配置するHTML
    private const val TEMPLATE_RESOURCE = "/web/authorize.html"

    // index.html にあるプレースホルダ。タグごと一致させて、バンドル中の文字列を誤って置換しないようにする
    internal const val PLACEHOLDER = """<script id="mineauth-model" type="application/json">__MINEAUTH_MODEL__</script>"""

    // nullのnonceはキーごと省略し、フロント側で「存在しない」と判定できるようにする
    private val pageJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    // テンプレートは不変なので初回アクセス時に一度だけ読み込む
    private val template: String by lazy {
        val stream = AuthorizePageRenderer::class.java.getResourceAsStream(TEMPLATE_RESOURCE)
            ?: error("$TEMPLATE_RESOURCE is missing from the plugin JAR. The frontend build may have been skipped.")
        stream.use { it.readBytes().decodeToString() }
    }

    /**
     * ビューモデルを埋め込んだ認可画面のHTMLを返す
     *
     * @param model 認可画面に表示するデータ
     * @return クライアントに返すHTML
     */
    fun render(model: AuthorizePageModel): String = render(template, model)

    /**
     * 指定したテンプレートにビューモデルを埋め込む
     *
     * @param template プレースホルダを含むHTML
     * @param model 認可画面に表示するデータ
     * @return プレースホルダを置き換えたHTML
     */
    internal fun render(template: String, model: AuthorizePageModel): String {
        val payload = escapeForScript(pageJson.encodeToString(AuthorizePageModel.serializer(), model))
        return template.replaceFirst(
            PLACEHOLDER,
            """<script id="mineauth-model" type="application/json">$payload</script>""",
        )
    }

    /**
     * `<script>` 要素の中に置いても安全なようにJSONをエスケープする
     *
     * kotlinx.serialization は `<` をエスケープしないため、client_name や state に
     * `</script>` が含まれるとスクリプト要素を抜け出せてしまう。
     * JSON文字列中では `<` などのUnicodeエスケープが同じ文字として解釈されるので、意味を変えずに無害化できる
     *
     * @param json エンコード済みのJSON
     * @return HTMLの特殊文字をUnicodeエスケープしたJSON
     */
    internal fun escapeForScript(json: String): String = buildString(json.length) {
        json.forEach { c ->
            when (c) {
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                // 古いJavaScriptエンジンでは文字列リテラル中の行区切り文字が構文エラーになる
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                else -> append(c)
            }
        }
    }
}
