package party.morino.mineauth.core.utils

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * OAuthクライアントに登録するリダイレクトURIの検証を行う
 * ダイアログとコマンドの両方から使うため、外部依存のない純粋関数としてまとめている
 *
 * http・httpsの登録値は、認可時（OAuthValidation.validateRedirectUri）に正規表現としても評価される。
 * そのためURIとして正しいだけでは足りず、正規表現として評価したときに
 * スキームやループバックの制限を抜けられないことも確認する。
 * 逆ドメイン形式のカスタムスキーム（スキームに . を含む）の登録値は、認可時に文字列としてのみ比較される。
 */
object RedirectUriValidator {
    // 1クライアントに登録できるリダイレクトURIの上限（認可時に全件を照合するため増やしすぎない）
    const val MAX_REDIRECT_URIS = 10

    // 1件あたりの最大長（oauth_client_redirect_uris.redirect_uri のカラム長と合わせる）
    private const val MAX_URI_LENGTH = 2048

    // スクリプト実行やローカルファイル参照につながるため常に拒否するスキーム
    private val FORBIDDEN_SCHEMES = setOf("javascript", "data", "file", "vbscript")

    // RFC 9700 Section 2.6 / RFC 8252 Section 7.3: httpを許可するループバックホスト
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    // RFC 3986 Section 3.1: 先頭のスキーム部分（URIとして解釈できない正規表現パターンにも使う）
    private val SCHEME_PREFIX = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")

    // httpsの正規表現パターンの先頭（https:///path のようにホストのないものは受け付けない）
    private val HTTPS_PATTERN_PREFIX = Regex("""^https://[^/]""")

    // httpのURIで、オーソリティの直後に続けてよい部分
    // 登録値は正規表現としても評価されるため、/? や /* のようにパス区切りを省略できる形は許可しない
    // 省略できると http://localhost/?.* が http://localhost.evil.example/ に一致してしまう
    private val FIXED_PATH_START = Regex("""^/(?![?*+{])""")

    // httpの正規表現パターンで許可するポート指定（数字だけに一致する形に限る）
    // .* などを許すと http://localhost:.*/ が http://localhost:x@evil.example/ に一致してしまう
    private const val PORT_TOKEN = """:(?:\d+|(?:\\d|\[0-9\])(?:\+|\{\d+(?:,\d*)?\}))"""

    // httpの正規表現パターンの先頭：ループバックホストをリテラルで書き、ポートとパス区切りまで固定する
    // 127.0.0.1 の . や [::1] の [] は正規表現では別の意味になるため、エスケープされた形だけを受け付ける
    private val LOOPBACK_PATTERN_PREFIX = Regex(
        """^http://(?:localhost|127\\\.0\\\.0\\\.1|\\\[::1\\\])""" +
            """(?:$PORT_TOKEN|\((?:\?:)?$PORT_TOKEN\)\?)?""" +
            """(?:/(?![?*+{])|\$?$)"""
    )

    /**
     * 改行区切りの入力をリダイレクトURIの一覧に変換して検証する
     * 前後の空白と空行は取り除き、重複は1件にまとめる
     *
     * @param input ダイアログなどの入力値（1行に1つのURI）
     * @return 成功時は登録順のURI一覧、失敗時は拒否理由
     */
    fun parseAndValidate(input: String): Either<RedirectUriError, List<String>> {
        val uris = input.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return validateAll(uris)
    }

    /**
     * リダイレクトURIの一覧を検証する
     *
     * @param uris 登録しようとしているURI一覧
     * @return 成功時は同じ一覧、失敗時は最初に見つかった拒否理由
     */
    fun validateAll(uris: List<String>): Either<RedirectUriError, List<String>> {
        if (uris.isEmpty()) {
            return RedirectUriError.Empty.left()
        }
        if (uris.size > MAX_REDIRECT_URIS) {
            return RedirectUriError.TooMany(MAX_REDIRECT_URIS).left()
        }
        // 最初に見つかった不正なURIの理由をエラーとして返す
        uris.forEach { uri -> validate(uri).onLeft { error -> return error.left() } }
        return uris.right()
    }

    /**
     * 登録済みの一覧に1件追加できるか検証する
     * 登録済みの値は以前の規則で登録された可能性があるため再検証しない
     * （再検証すると、古い値を持つクライアントにURIを追加できなくなる）
     *
     * @param current 登録済みのURI一覧
     * @param uri 追加するURI
     * @return 成功時は追加後の一覧、失敗時は拒否理由
     */
    fun validateAddition(current: List<String>, uri: String): Either<RedirectUriError, List<String>> {
        if (current.size >= MAX_REDIRECT_URIS) {
            return RedirectUriError.TooMany(MAX_REDIRECT_URIS).left()
        }
        return validate(uri).map { current + it }
    }

    /**
     * 1件のリダイレクトURIを登録規則に照らして検証する
     * 通常のURIに加えて、認可時の照合がサポートしている正規表現パターンも受け付ける
     *
     * @param uri 検証するURIまたは正規表現パターン
     * @return 成功時は同じ値、失敗時は拒否理由
     */
    fun validate(uri: String): Either<RedirectUriError, String> {
        if (uri.length > MAX_URI_LENGTH) {
            return RedirectUriError.TooLong(MAX_URI_LENGTH).left()
        }
        // RFC 6749 Section 3.1.2: URIでも正規表現パターンでもフラグメントは許可しない
        if ('#' in uri) {
            return RedirectUriError.FragmentNotAllowed(uri).left()
        }

        // スキームは大文字小文字を区別しないため小文字にそろえて判定する（先頭の^は任意）
        val scheme = SCHEME_PREFIX.find(uri.removePrefix("^"))?.groupValues?.get(1)?.lowercase()
            ?: return RedirectUriError.InvalidFormat(uri).left()
        val parsed = parseUriOrNull(uri)

        return when {
            scheme in FORBIDDEN_SCHEMES -> RedirectUriError.ForbiddenScheme(uri, scheme).left()
            // URIとしてホストを読み取れないものは正規表現パターンとして扱う
            scheme == "https" -> if (parsed?.host != null) uri.right() else validatePattern(uri)
            scheme == "http" -> if (parsed?.host != null) validateHttpUri(uri, parsed) else validatePattern(uri)
            // RFC 8252 Section 7.1: カスタムスキームは逆ドメイン形式（ドットを含む）のみ許可する
            '.' in scheme -> validatePrivateUseUri(uri, parsed)
            else -> RedirectUriError.UnsupportedScheme(uri, scheme).left()
        }
    }

    /**
     * URIとして解釈する
     *
     * @return 解釈できない場合はnull（正規表現パターンの可能性がある）
     */
    private fun parseUriOrNull(uri: String): URI? = try {
        URI(uri)
    } catch (e: URISyntaxException) {
        null
    }

    /**
     * httpのURIを検証する
     * RFC 9700 Section 2.6 に従いループバックホストのみ許可し、ユーザー情報を含むものは拒否する
     *
     * @param uri 検証するURI
     * @param parsed uriを解釈した結果（ホストがあるもの）
     * @return 成功時は同じ値、失敗時は拒否理由
     */
    private fun validateHttpUri(uri: String, parsed: URI): Either<RedirectUriError, String> {
        if (parsed.host.lowercase() !in LOOPBACK_HOSTS) {
            return RedirectUriError.InsecureHttp(uri).left()
        }
        // java.net.URIはユーザー情報に . * + ( ) を許すため、http://.*@localhost/ もホストがlocalhostとして解釈される
        // 認可時に正規表現として評価すると http://evil.example/@localhost/ に一致してしまうので、ユーザー情報自体を拒否する
        if (parsed.rawUserInfo != null) {
            return RedirectUriError.HttpUserInfo(uri).left()
        }
        // 認可時には正規表現としても評価されるため、オーソリティの直後でホストが固定されていることを確認する
        val afterAuthority = uri.substringAfter("://").removePrefix(parsed.rawAuthority)
        return if (afterAuthority.isEmpty() || FIXED_PATH_START.containsMatchIn(afterAuthority)) {
            uri.right()
        } else {
            RedirectUriError.UnsafeLoopbackPattern(uri).left()
        }
    }

    /**
     * 逆ドメイン形式のカスタムスキームを検証する
     * buildSuccessRedirectUri / buildErrorRedirectUri が使うKtorのURLBuilderは、
     * オーソリティがないと com.example.app:/cb を com.example.app://localhost/cb に書き換え、
     * スキームも小文字にする。認可コードが別のURIに送られないよう、そのまま保たれる形だけを許可する
     *
     * @param uri 検証するURI
     * @param parsed uriを解釈した結果（解釈できない場合はnull）
     * @return 成功時は同じ値、失敗時は拒否理由
     */
    private fun validatePrivateUseUri(uri: String, parsed: URI?): Either<RedirectUriError, String> {
        val keptIntactByUrlBuilder = parsed?.host != null && parsed.scheme == parsed.scheme.lowercase()
        return if (keptIntactByUrlBuilder) uri.right() else RedirectUriError.UnsupportedPrivateUseForm(uri).left()
    }

    /**
     * 正規表現パターンを検証する
     * httpsで始まるか、httpならループバックホストに固定されているパターンのみ受け付ける
     *
     * @param uri 検証するパターン（先頭の^は任意）
     * @return 成功時は同じ値、失敗時は拒否理由
     */
    private fun validatePattern(uri: String): Either<RedirectUriError, String> {
        val pattern = uri.removePrefix("^")
        if (pattern.startsWith("http://")) {
            if (!LOOPBACK_PATTERN_PREFIX.containsMatchIn(pattern)) {
                return RedirectUriError.UnsafeLoopbackPattern(uri).left()
            }
        } else if (!HTTPS_PATTERN_PREFIX.containsMatchIn(pattern)) {
            return RedirectUriError.InvalidFormat(uri).left()
        }

        // 正規表現としてコンパイルできることを確認する
        try {
            Pattern.compile(uri)
        } catch (e: PatternSyntaxException) {
            return RedirectUriError.InvalidFormat(uri).left()
        }

        return if (hasTopLevelAlternation(uri)) {
            RedirectUriError.TopLevelAlternation(uri).left()
        } else {
            uri.right()
        }
    }

    /**
     * 先頭階層に選択（|）があるかを正規表現エンジンに判定させる
     * https://a|javascript:.* のようなパターンは、先頭の確認を通っても2つ目の選択肢で任意のURIに一致してしまう
     *
     * \Q..\E や [](] のような構文は文字列の走査では解釈しきれないため、エンジンの解析結果を使う。
     * 必ず失敗する (?!) を先頭に付けると、1つ目の選択肢は入力を読まずに失敗する。
     * 先頭階層の選択がなければ照合はそこで終わるが、あれば次の選択肢に進み、
     * 空の入力に一致するか、入力の終端に達する。
     *
     * @param pattern コンパイルできることを確認済みのパターン
     * @return 先頭階層に選択がある場合true
     */
    private fun hasTopLevelAlternation(pattern: String): Boolean {
        val matcher = Pattern.compile("(?!)$pattern").matcher("")
        return matcher.lookingAt() || matcher.hitEnd()
    }
}
