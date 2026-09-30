package party.morino.mineauth.core.web.page

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.api.config.PluginDirectory
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.web.router.auth.data.AuthorizePageModel
import java.io.File

/**
 * 認可画面のHTMLを組み立てる
 *
 * フロントエンド（core/src/main/frontend）のビルド成果物はJS/CSSをインライン化した1枚のHTMLで、
 * ビューモデルを受け取る `<script type="application/json">` のプレースホルダを持つ。
 * ここではそのプレースホルダをビューモデルのJSONで置き換えるだけで、描画はReactに任せる
 *
 * プラグインディレクトリに `web/authorize.html` を置くと、JAR同梱のHTMLの代わりにそれを使う。
 * サーバーごとにデザインを変えた認可画面を、プラグインを再ビルドせずに差し替えるための仕組み
 */
object AuthorizePageRenderer : KoinComponent {
    // Gradleのフロントエンドビルドタスクがクラスパスに配置するHTML
    private const val TEMPLATE_RESOURCE = "/web/authorize.html"

    // プラグインディレクトリからの相対パス。assets/ 配下は静的配信されるため、公開されない場所に置く
    internal const val OVERRIDE_PATH = "web/authorize.html"

    // index.html にあるプレースホルダ。タグごと一致させて、バンドル中の文字列を誤って置換しないようにする
    internal const val PLACEHOLDER = """<script id="mineauth-model" type="application/json">__MINEAUTH_MODEL__</script>"""

    // nullのnonceはキーごと省略し、フロント側で「存在しない」と判定できるようにする
    private val pageJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    // JAR同梱のテンプレートは不変なので初回アクセス時に一度だけ読み込む
    private val bundledTemplate: String by lazy {
        val stream = AuthorizePageRenderer::class.java.getResourceAsStream(TEMPLATE_RESOURCE)
            ?: error("$TEMPLATE_RESOURCE is missing from the plugin JAR. The frontend build may have been skipped.")
        stream.use { it.readBytes().decodeToString() }
    }

    /**
     * 上書き用テンプレートの読み込み結果のキャッシュ
     * ファイルの場所・更新日時・サイズが変わるまでは読み直さず、変わったら次のリクエストで反映する
     */
    private data class CachedOverride(
        val path: String,
        val lastModified: Long,
        val length: Long,
        val template: Either<String, String>,
    )

    @Volatile
    private var cachedOverride: CachedOverride? = null

    /**
     * ビューモデルを埋め込んだ認可画面のHTMLを返す
     *
     * @param model 認可画面に表示するデータ
     * @return クライアントに返すHTML
     */
    fun render(model: AuthorizePageModel): String = render(currentTemplate(), model)

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
     * 上書き用テンプレートとして使えるか検査する
     *
     * プレースホルダが無いHTMLを使うと、ビューモデルが埋め込まれず画面が読み込みエラーになる。
     * 気付かないまま壊れた画面を出さないよう、その場合は使わずにJAR同梱のHTMLへ戻す
     *
     * @param html 上書き用ファイルの内容
     * @return 使える場合はHTML、使えない場合は理由
     */
    internal fun validateOverride(html: String): Either<String, String> =
        if (html.contains(PLACEHOLDER)) {
            html.right()
        } else {
            "it does not contain the model placeholder ($PLACEHOLDER)".left()
        }

    /**
     * 今回のリクエストで使うテンプレートを返す
     * 上書き用ファイルがあり、かつ使える場合はそれを、そうでなければJAR同梱のHTMLを返す
     */
    private fun currentTemplate(): String {
        // object は Koin より長生きするため、by inject() で保持せず毎回取得する（プラグインの再読み込みで Koin が作り直されても追従する）
        val file = File(get<PluginDirectory>().getRootDirectory(), OVERRIDE_PATH)
        if (!file.isFile) {
            cachedOverride = null
            return bundledTemplate
        }
        return loadOverride(file).getOrNull() ?: bundledTemplate
    }

    /**
     * 上書き用ファイルを読み込む。ファイルが変わったときだけ読み直し、そのときに結果をログへ出す
     *
     * @param file 上書き用ファイル
     * @return 使える場合はHTML、使えない場合は理由
     */
    private fun loadOverride(file: File): Either<String, String> {
        val path = file.absolutePath
        val lastModified = file.lastModified()
        val length = file.length()
        cachedOverride
            ?.takeIf { it.path == path && it.lastModified == lastModified && it.length == length }
            ?.let { return it.template }

        val template = Either.catch { file.readText() }
            .mapLeft { "it could not be read: ${it.message}" }
            .flatMap(::validateOverride)
        val logger = get<MineAuth>().logger
        template.fold(
            { reason -> logger.warning("Ignoring ${file.path} because $reason. Using the bundled authorization page.") },
            { logger.info("Using the custom authorization page: ${file.path}") },
        )
        cachedOverride = CachedOverride(path, lastModified, length, template)
        return template
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
