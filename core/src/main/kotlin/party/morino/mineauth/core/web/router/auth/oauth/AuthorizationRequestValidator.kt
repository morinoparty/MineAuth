package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.ktor.http.Parameters
import party.morino.mineauth.core.web.components.auth.ClientData

/**
 * 認可リクエストの検証
 *
 * GET・POSTの認可リクエストとログインの送信で検証がずれると、片方だけオープンリダイレクタになるなどの穴が生まれる。
 * そのため検証は全てここを通し、ルーターは結果に応じた応答だけを担当する
 */
object AuthorizationRequestValidator {
    // 認可コードフローのみをサポートする
    private const val RESPONSE_TYPE_CODE = "code"

    // 他の値と併用できないprompt値
    private const val PROMPT_NONE = "none"

    /**
     * 認可リクエストのパラメータを検証する
     *
     * client_idとredirect_uriを最初に検証し、以降のエラーは検証済みのredirect_uriへのリダイレクトで通知する
     * （RFC 6749 Section 4.1.2.1）。検証順はドキュメントに記載しているため、変更する場合は合わせて更新すること
     *
     * パラメータはreadSingleParameterで読み取る。Parameters.get()は重複時に先頭の値を黙って採用するので、
     * prompt=login&prompt=none や request=&request=jwt のようなリクエストが検証をすり抜けてしまう
     *
     * @param params クエリまたはフォームのパラメータ
     * @param findClient クライアントIDから登録情報を取得する関数
     * @return 検証済みのリクエスト、または応答方法を区別したエラー
     */
    fun validateAuthorizationRequest(
        params: Parameters,
        findClient: (String) -> ClientData? = OAuthService::getClientData,
    ): Either<AuthorizeError, ValidatedAuthorizeRequest> = either {
        // ここまでの失敗ではredirect_uriを信頼できないので、ShowErrorのみを返す
        // redirect_uriが重複している場合はどの値も信頼できないので、重複もリダイレクトしない
        val readBeforeRedirectValidation = { name: String ->
            params.readSingleParameter(name).mapLeft { AuthorizeError.ShowError(it.message) }.bind()
        }
        val clientId = ensureNotNull(readBeforeRedirectValidation("client_id")) {
            AuthorizeError.ShowError("Invalid request: missing client_id or redirect_uri")
        }
        val redirectUri = ensureNotNull(readBeforeRedirectValidation("redirect_uri")) {
            AuthorizeError.ShowError("Invalid request: missing client_id or redirect_uri")
        }
        val client = ensureNotNull(findClient(clientId)) { AuthorizeError.ShowError("Invalid client") }
        ensure(OAuthValidation.validateRedirectUri(client, redirectUri)) {
            AuthorizeError.ShowError("Invalid redirect_uri")
        }

        // ここからはredirect_uriを信頼できるので、エラーはリダイレクトで通知する
        // stateが重複している場合はどの値を返すべきか判断できないので、stateを付けずに通知する
        val state = params.readSingleParameter("state").mapLeft {
            AuthorizeError.RedirectWithError(redirectUri, OAuthErrorCode.INVALID_REQUEST, it.message, null)
        }.bind()
        val redirectError = { code: OAuthErrorCode, description: String ->
            AuthorizeError.RedirectWithError(redirectUri, code, description, state)
        }

        // RFC 6749 Section 3.1: 重複を値の検証より先に拒否する（検証順の結果が読み取りの順序に左右されないようにする）
        val read = { name: String ->
            params.readSingleParameter(name).mapLeft { redirectError(OAuthErrorCode.INVALID_REQUEST, it.message) }.bind()
        }
        val request = read("request")
        val requestUri = read("request_uri")
        val prompt = read("prompt")
        val responseType = read("response_type")
        val scope = read("scope")
        val codeChallenge = read("code_challenge")
        val codeChallengeMethod = read("code_challenge_method")
        val nonce = read("nonce")
        // ログインの送信も同じ検証を通るので、認証情報の重複もここで拒否する
        // 値は使わない（空白だけのパスワードも有効なので、ルーターがnonEmptyValueで読み取る）
        read("username")
        read("password")

        // OIDC Core 1.0 Section 6.1 / 6.2: 未対応のパラメータは無視せず拒否する
        ensure(request == null) {
            redirectError(OAuthErrorCode.REQUEST_NOT_SUPPORTED, "The request parameter is not supported")
        }
        ensure(requestUri == null) {
            redirectError(OAuthErrorCode.REQUEST_URI_NOT_SUPPORTED, "The request_uri parameter is not supported")
        }

        // OIDC Core 1.0 Section 3.1.2.1: noneは他の値と併用できない
        // SSOセッションを持たないのでprompt=noneは必ずログインが必要になる。login等は常に画面を出すので満たしている
        val prompts = prompt?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        if (PROMPT_NONE in prompts) {
            ensure(prompts.size == 1) {
                redirectError(OAuthErrorCode.INVALID_REQUEST, "The prompt value none cannot be combined with other values")
            }
            raise(redirectError(OAuthErrorCode.LOGIN_REQUIRED, "The user is not authenticated"))
        }

        // RFC 6749 Section 4.1.2.1: 欠落はinvalid_request、未対応の値はunsupported_response_type
        ensureNotNull(responseType) {
            redirectError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: response_type")
        }
        ensure(responseType == RESPONSE_TYPE_CODE) {
            redirectError(OAuthErrorCode.UNSUPPORTED_RESPONSE_TYPE, "Only the code response_type is supported")
        }

        // RFC 6749ではRECOMMENDEDだが、CSRF対策としてMineAuthでは必須にしている
        val requiredState = ensureNotNull(state) {
            redirectError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: state")
        }

        // RFC 6749 Section 3.3: デフォルトスコープを持たないので、欠落はinvalid_scopeで失敗させる
        val requestedScope = ensureNotNull(scope) {
            redirectError(OAuthErrorCode.INVALID_SCOPE, "Missing required parameter: scope")
        }
        val grantedScopes = OAuthScope.resolveGranted(requestedScope).mapLeft { invalid ->
            redirectError(OAuthErrorCode.INVALID_SCOPE, "Invalid scope(s): ${invalid.unknownValues.joinToString(", ")}")
        }.bind()

        // RFC 7636: 省略時の既定はplainになるが、S256のみ対応なので省略も拒否する
        val requiredCodeChallenge = ensureNotNull(codeChallenge) {
            redirectError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: code_challenge")
        }
        ensure(OAuthValidation.validatePKCE(requiredCodeChallenge, codeChallengeMethod)) {
            redirectError(OAuthErrorCode.INVALID_REQUEST, "This server only supports S256 code_challenge_method")
        }

        ValidatedAuthorizeRequest(
            clientId = client.clientId,
            clientName = client.clientName,
            redirectUri = redirectUri,
            state = requiredState,
            grantedScopes = grantedScopes,
            codeChallenge = requiredCodeChallenge,
            // validatePKCEを通過しているのでnullにはならない
            codeChallengeMethod = checkNotNull(codeChallengeMethod),
            nonce = nonce,
        )
    }

    /**
     * POSTがログインの送信かどうかを判定する
     * 認可リクエストをフォームで送るRPと、ログインフォームが同じURLへ送信するため、認証情報の有無で区別する
     *
     * @param params フォームのパラメータ
     * @return usernameまたはpasswordが含まれていればtrue
     */
    fun isLoginSubmission(params: Parameters): Boolean = "username" in params || "password" in params

    /**
     * ログインの送信の認証情報を取得する
     * RFC 6749 Section 3.1: 値の無いパラメータは省略されたものとして扱う
     *
     * 空白だけのパスワードも有効な値なので、空白を省略扱いにするreadSingleParameterは使わない。
     * 重複はvalidateAuthorizationRequestで拒否済みの前提で読み取る
     *
     * @param name パラメータ名
     * @return 値。無いか空の場合はnull
     */
    internal fun Parameters.nonEmptyValue(name: String): String? = this[name]?.ifEmpty { null }
}
