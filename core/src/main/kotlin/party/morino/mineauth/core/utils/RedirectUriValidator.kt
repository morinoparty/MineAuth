package party.morino.mineauth.core.utils

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.net.URI

/**
 * OAuthクライアントに登録するリダイレクトURIの検証を行う
 * ダイアログとコマンドの両方から使うため、外部依存のない純粋関数としてまとめている
 */
object RedirectUriValidator {
    // 1クライアントに登録できるリダイレクトURIの上限（認可時に全件を照合するため増やしすぎない）
    const val MAX_REDIRECT_URIS = 10

    // 1件あたりの最大長（oauth_client_redirect_uris.redirect_uri のカラム長と合わせる）
    private const val MAX_URI_LENGTH = 2048

    /**
     * 改行区切りの入力をリダイレクトURIの一覧に変換して検証する
     * 前後の空白と空行は取り除き、重複は1件にまとめる
     *
     * @param input ダイアログなどの入力値（1行に1つのURI）
     * @return 成功時は登録順のURI一覧、失敗時はエラーメッセージ
     */
    fun parseAndValidate(input: String): Either<String, List<String>> {
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
     * @return 成功時は同じ一覧、失敗時はエラーメッセージ
     */
    fun validateAll(uris: List<String>): Either<String, List<String>> {
        if (uris.isEmpty()) {
            return "リダイレクトURIを入力してください".left()
        }
        if (uris.size > MAX_REDIRECT_URIS) {
            return "リダイレクトURIは${MAX_REDIRECT_URIS}件まで登録できます".left()
        }
        // 最初に見つかった不正なURIをエラーとして返す
        uris.firstOrNull { !isValid(it) }?.let { invalid ->
            return "無効なリダイレクトURI形式です: $invalid".left()
        }
        return uris.right()
    }

    /**
     * 1件のリダイレクトURIの形式を確認する
     * 認可時の検証（OAuthValidation.validateRedirectUri）が正規表現パターンを
     * サポートしているため、通常のURIに加えて正規表現パターンも受け付ける
     *
     * @param uri 検証するURI
     * @return 登録できる形式ならtrue
     */
    fun isValid(uri: String): Boolean {
        if (uri.length > MAX_URI_LENGTH) {
            return false
        }

        // 通常のURIとしてパースできればOK（最も一般的なケース）
        val isPlainUri = try {
            val parsed = URI(uri)
            // スキームとホストが存在することを確認
            parsed.scheme != null && parsed.host != null
        } catch (e: Exception) {
            false
        }
        if (isPlainUri) {
            return true
        }

        // 正規表現パターンとして受け付ける場合は、オープンリダイレクトを防ぐため
        // http(s)スキームで始まるパターンのみ許可する（先頭の^は任意）
        if (!uri.removePrefix("^").startsWith("http")) {
            return false
        }
        return try {
            // 正規表現としてコンパイルできることを確認
            Regex(uri)
            true
        } catch (e: Exception) {
            false
        }
    }
}
