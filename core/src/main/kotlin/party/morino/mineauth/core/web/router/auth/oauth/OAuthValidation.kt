package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.repository.OAuthClientError
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import java.security.MessageDigest
import java.util.*
import java.util.regex.PatternSyntaxException

object OAuthValidation : KoinComponent {

    /**
     * クライアントIDでクライアントデータを取得・検証する
     * DBからクライアント情報を取得
     *
     * @param clientId クライアントID
     * @return クライアントデータ、見つからない場合はnull
     */
    fun validateAndGetClientData(clientId: String?): ClientData? {
        if (clientId == null) return null
        return runBlocking { ClientData.getClientDataFromDb(clientId) }
    }

    /**
     * Validate the redirect URI.
     * 登録されたredirect_uriのいずれかと照合して検証する
     * http・httpsなどの登録値は正規表現パターンとして、スキームに . や + を含む登録値は文字列として比較し、
     * どちらも登録値そのものに加えてサブパスも一致させる（詳細はmatchesRedirectUri）
     *
     * @param clientData クライアントデータ
     * @param redirectUri 検証するリダイレクトURI
     * @return 有効な場合true
     */
    fun validateRedirectUri(clientData: ClientData, redirectUri: String): Boolean {
        // RFC 6749 Section 3.1.2: フラグメントを含むURIは登録値に関係なく受け付けない
        // Ktorがデコードした後の値を見るため、%23 で送られたものもここで拒否される
        if ('#' in redirectUri) {
            return false
        }

        // 登録済みURIのいずれかに一致すれば有効とする
        return clientData.redirectUris.any { registeredUri -> matchesRedirectUri(registeredUri, redirectUri) }
    }

    // RFC 3986 Section 3.1 のスキームのうち、正規表現として特別な意味を持つ . か + を含むもの（先頭の^は任意）
    private val REGEX_UNSAFE_SCHEME = Regex("""^\^?[A-Za-z][A-Za-z0-9\-]*[.+][A-Za-z0-9+.\-]*:""")

    /**
     * 1件の登録URIとリクエストのリダイレクトURIを照合する
     *
     * まず完全一致を確認し、一致しなければ登録値のスキームで比較方法を分ける。
     * スキームに . や + を含む登録値（逆ドメイン形式のカスタムスキームなど）は文字列としてのみ比較し、
     * 登録値のサブパスも一致とする。先頭の^は無視する。
     * 正規表現として評価すると h.tp://evil.example/cb が http://evil.example/cb に一致し、
     * 登録時のスキーム制限を抜けて認可コードを平文で送れてしまうため。
     * それ以外の登録値は正規表現パターンとして評価し、登録値そのものとサブパスに一致させる。
     *
     * @param registeredUri 登録済みのリダイレクトURI（http・httpsは正規表現パターン可）
     * @param redirectUri 検証するリダイレクトURI
     * @return 一致する場合true
     */
    private fun matchesRedirectUri(registeredUri: String, redirectUri: String): Boolean {
        // 完全一致チェック（最も一般的なケース）
        if (registeredUri == redirectUri) {
            return true
        }

        if (REGEX_UNSAFE_SCHEME.containsMatchIn(registeredUri)) {
            // 先頭の^は正規表現のアンカーであり、文字列として比較するときはURIの一部ではない
            val literalUri = registeredUri.removePrefix("^")
            return redirectUri == literalUri || redirectUri.startsWith(literalUri.removeSuffix("/") + "/")
        }

        // 登録URIを正規表現パターンに変換
        // 末尾が/の登録値は.*を付けてサブパスを許可する
        // それ以外は(/.*)?を付け、正規表現で登録したパターンそのものに一致するURIとサブパスの両方を許可する
        val pattern = if (registeredUri.endsWith("/")) {
            registeredUri + ".*"
        } else {
            registeredUri + "(/.*)?"
        }

        return try {
            Regex(pattern).matches(redirectUri)
        } catch (e: PatternSyntaxException) {
            // 正規表現として解釈できない登録値は完全一致でのみ使える
            false
        }
    }

    fun validatePKCE(codeChallenge: String?, codeChallengeMethod: String?): Boolean {
        return !(codeChallenge == null || codeChallengeMethod != "S256")
    }

    // RFC 7636 Section 4.1: code_verifierの許容文字種 [A-Za-z0-9-._~]
    private val CODE_VERIFIER_PATTERN = Regex("^[A-Za-z0-9\\-._~]{43,128}$")

    /**
     * code_verifierの形式を検証する
     * RFC 7636 Section 4.1: 43〜128文字、[A-Za-z0-9-._~]のみ許可
     *
     * @param codeVerifier 検証するcode_verifier
     * @return 有効な場合true
     */
    fun isValidCodeVerifierFormat(codeVerifier: String): Boolean {
        return CODE_VERIFIER_PATTERN.matches(codeVerifier)
    }

    /**
     * code_verifierとcode_challengeを検証する（S256方式）
     * RFC 7636 Section 4.6: SHA-256ハッシュとBase64URLエンコードで比較
     *
     * @param codeChallenge 認可時に送信されたcode_challenge
     * @param codeVerifier トークン交換時に送信されたcode_verifier
     * @return 一致する場合true
     */
    fun validateCodeVerifier(codeChallenge: String, codeVerifier: String): Boolean {
        // RFC 7636 Section 4.1: code_verifierの長さ・文字種を検証
        if (!isValidCodeVerifierFormat(codeVerifier)) return false
        // RFC 7636 Section 4.6: ASCII エンコーディングを明示的に指定
        val hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        return codeChallenge == base64
    }

    /**
     * 認可エンドポイントのエラーリダイレクト先を組み立てる
     * 検証済みのredirect_uriにのみ使うこと（未検証の値を渡すとオープンリダイレクタになる）
     *
     * @param redirectUri 検証済みのリダイレクトURI
     * @param error エラーコード
     * @param errorDescription エラーの説明（許可されない文字はここで取り除く）
     * @param state リクエストのstate。リクエストに無かった場合はnull
     * @return エラーパラメータを付加したURI
     */
    fun buildErrorRedirectUri(redirectUri: String, error: String, errorDescription: String, state: String?): String {
        val uri = URLBuilder()
        uri.takeFrom(redirectUri)
        uri.parameters.apply {
            append("error", error)
            // 全ての呼び出し元を一度に守るため、呼び出し側ではなくここで無害化する
            sanitizeErrorDescription(errorDescription)?.let { append("error_description", it) }
            // RFC 6749 Section 4.1.2.1: stateはリクエストに含まれていた場合のみ返す
            state?.let { append("state", it) }
        }
        return uri.buildString()
    }

    fun buildSuccessRedirectUri(redirectUri: String, code: String, state: String): String {
        val uri = URLBuilder()
        uri.takeFrom(redirectUri)
        uri.parameters.apply {
            append("code", code)
            append("state", state)
        }
        return uri.buildString()
    }

    /**
     * Authorizationヘッダーの値をBasic認証のクレデンシャルとして解釈する
     * RFC 6749 Section 2.3.1 (client_secret_basic) 準拠
     *
     * ヘッダーがない場合やBasic以外のスキームは「Basic認証を使っていない」としてnullを返す。
     * Basicを名乗っているのに解釈できない場合は、client_id欠如へ流さず認証失敗として扱う
     * （RFC 6749 Section 5.2: ヘッダーで認証を試みたクライアントには401を返す）。
     *
     * @param authHeader Authorizationヘッダーの値
     * @return Pair(client_id, client_secret)、Basic認証でない場合はnull、または認証エラー
     */
    fun extractBasicCredentials(authHeader: String?): Either<ClientAuthFailure, Pair<String, String>?> {
        if (authHeader == null) return null.right()
        // スキーム名を比較する（"Basic" のみでトークンがないヘッダーも不正なBasicとして検出するため）
        if (!authHeader.substringBefore(' ').equals("Basic", ignoreCase = true)) return null.right()

        val malformed = ClientAuthFailure(
            OAuthErrorCode.INVALID_CLIENT,
            "Malformed Basic authorization header"
        ).left()

        // Base64デコードして "client_id:client_secret" 形式を分割
        val decoded = try {
            String(Base64.getDecoder().decode(authHeader.substringAfter(' ', "").trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return malformed
        }
        val colonIndex = decoded.indexOf(':')
        if (colonIndex < 0) return malformed

        // Basicのユーザー名は常にクライアントIDになるため、空のユーザー名は解釈できないものとして扱う
        val id = decoded.substring(0, colonIndex)
        if (id.isBlank()) return malformed

        return Pair(id, decoded.substring(colonIndex + 1)).right()
    }

    /**
     * Authorizationヘッダーとフォームパラメータからクライアントクレデンシャルを抽出する
     *
     * RFC 6749 Section 2.3: 認証方式の併用を禁止する。第2の認証方式になるのは本文のclient_secretのみで、
     * 本文のclient_idは識別用（RFC 6749 Section 3.2.1）なのでBasic認証と同時に送られても併用にはならない。
     *
     * @param authHeader Authorizationヘッダーの値
     * @param formParameters フォームパラメータ
     * @param requireClientAuthentication クライアント認証が必須のエンドポイントならtrue。
     *   トークンエンドポイントはPublicクライアントも受け付けるので、client_idの欠落は単なるパラメータ不足として扱う
     * @return 抽出されたクレデンシャル、または認証エラー
     */
    fun extractClientCredentials(
        authHeader: String?,
        formParameters: Parameters,
        requireClientAuthentication: Boolean = false
    ): Either<ClientAuthFailure, ClientCredentials> = either {
        // 解釈できないBasicヘッダーは、本文の内容に関わらず認証失敗として返す
        val basicCredentials = extractBasicCredentials(authHeader).bind()
        val bodyClientId = formParameters.readClientAuthParameter("client_id").bind()
        val bodyClientSecret = formParameters.readClientAuthParameter("client_secret").bind()

        if (basicCredentials == null) {
            // client_secret_post、またはclient_idのみを送るPublicクライアント
            val clientId = ensureNotNull(bodyClientId) {
                if (requireClientAuthentication) {
                    // RFC 6749 Section 5.2: 認証情報が1つも無いリクエストはinvalid_client
                    ClientAuthFailure(OAuthErrorCode.INVALID_CLIENT, "Client authentication required")
                } else {
                    ClientAuthFailure(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: client_id")
                }
            }
            return@either ClientCredentials(clientId, bodyClientSecret)
        }

        val (basicClientId, basicClientSecret) = basicCredentials
        ensure(bodyClientSecret == null) {
            ClientAuthFailure(
                OAuthErrorCode.INVALID_REQUEST,
                "Multiple client authentication methods are not allowed"
            )
        }
        // 本文のclient_idがBasicで認証するクライアントと別物を指すリクエストは受け付けない
        ensure(bodyClientId == null || bodyClientId == basicClientId) {
            ClientAuthFailure(OAuthErrorCode.INVALID_REQUEST, "client_id does not match the Basic credentials")
        }

        // クライアントIDは常にBasicのユーザー名を使う。secretが空文字の場合はPublicクライアントとして扱う
        ClientCredentials(basicClientId, basicClientSecret.ifEmpty { null })
    }

    /**
     * リクエストのAuthorizationヘッダーとフォームパラメータからクライアントクレデンシャルを抽出する
     *
     * @param formParameters フォームパラメータ
     * @param requireClientAuthentication クライアント認証が必須のエンドポイントならtrue
     * @return 抽出されたクレデンシャル、または認証エラー
     */
    fun RoutingContext.extractClientCredentials(
        formParameters: Parameters,
        requireClientAuthentication: Boolean = false
    ): Either<ClientAuthFailure, ClientCredentials> =
        extractClientCredentials(call.request.header(HttpHeaders.Authorization), formParameters, requireClientAuthentication)

    /**
     * クライアント認証用のパラメータを読み取り、失敗をクライアント認証の失敗情報に変換する
     *
     * @param name パラメータ名
     * @return パラメータの値（省略時はnull）、または認証エラー
     */
    private fun Parameters.readClientAuthParameter(name: String): Either<ClientAuthFailure, String?> =
        readSingleParameter(name).mapLeft { ClientAuthFailure(it.errorCode, it.message) }

    /**
     * Confidentialクライアントを認証する
     *
     * client_id, client_secretの存在確認、クライアントデータの取得、
     * クライアントタイプの検証、シークレットの検証を一括で行う
     * IntrospectRouter/RevokeRouter向け（Confidentialクライアントのみ許可）
     *
     * @param credentials 抽出済みのクレデンシャル
     * @param source リクエストの接続元（Argon2Gateで接続元ごとの検証の枠を割り当てるキー）
     * @return 認証済みのConfidentialClientData、または認証エラー（種類でバックオフに数えるかを区別する）
     */
    suspend fun authenticateConfidentialClient(
        credentials: ClientCredentials,
        source: ClientIpKey
    ): Either<ClientAuthFailure, ClientData.ConfidentialClientData> {
        // RFC 6749 Section 5.2: 認証情報なしはinvalid_client（authenticateClientと同じ応答に揃える）
        // 何も照合していないので推測の試行としては数えない（ログアウト時にrevokeを呼ぶPublicクライアントの利用者を巻き込まない）
        val clientSecret = credentials.clientSecret
            ?: return ClientAuthFailure(OAuthErrorCode.INVALID_CLIENT, "Client authentication required").left()

        // クライアントデータをDBから取得（存在しないclient_idは推測の試行として数え、DBの障害は数えない）
        val clientData = findClientToAuthenticate(credentials.clientId).getOrElse { return it.left() }

        // Confidentialクライアントのみ許可（シークレットを照合していないので数えない）
        if (clientData !is ClientData.ConfidentialClientData) {
            return ClientAuthFailure(OAuthErrorCode.INVALID_CLIENT, "Client type mismatch").left()
        }

        // Argon2idによる定数時間比較でシークレットを検証（タイミング攻撃対策）
        return verifyClientSecret(clientData, clientSecret, source).map { clientData }
    }

    /**
     * 任意のタイプのクライアントを認証する
     *
     * Confidential: client_secret必須、Argon2idで検証
     * Public: client_secretは不要（送信されても無視）
     * TokenRouter向け（両タイプのクライアントをサポート）
     *
     * @param credentials 抽出済みのクレデンシャル
     * @param source リクエストの接続元（Argon2Gateで接続元ごとの検証の枠を割り当てるキー）
     * @return 認証済みのClientData、または認証エラー（種類でバックオフに数えるかを区別する）
     */
    suspend fun authenticateClient(
        credentials: ClientCredentials,
        source: ClientIpKey
    ): Either<ClientAuthFailure, ClientData> {
        // クライアントデータをDBから取得（存在しないclient_idは推測の試行として数え、DBの障害は数えない）
        val clientData = findClientToAuthenticate(credentials.clientId).getOrElse { return it.left() }

        // DB上のクライアントタイプに基づいて認証を分岐（secretの有無ではなく登録タイプで判定）
        return when (clientData) {
            is ClientData.ConfidentialClientData -> {
                // RFC 6749 Section 2.3.1: Confidentialクライアントはclient_secret必須
                // 何も照合していないので推測の試行としては数えない
                val clientSecret = credentials.clientSecret
                    ?: return ClientAuthFailure(OAuthErrorCode.INVALID_CLIENT, "Client authentication required").left()
                // Argon2idによる定数時間比較で検証（タイミング攻撃対策）
                verifyClientSecret(clientData, clientSecret, source).map { clientData }
            }
            // Publicクライアント: client_secretが送られても無視する
            is ClientData.PublicClientData -> clientData.right()
        }
    }

    /**
     * クライアント認証のために、提示されたclient_idのクライアントをDBから取得する
     * 存在しないことを確認できた場合だけをclient_idの推測として扱い、DBを読めなかった場合はサーバーの障害として返す
     *
     * @param clientId 提示されたクライアントID
     * @return クライアントデータ、または認証エラー
     */
    private suspend fun findClientToAuthenticate(clientId: String): Either<ClientAuthFailure, ClientData> =
        when (val found = ClientData.findClientDataInDb(clientId)) {
            is Either.Left -> clientLookupFailed(clientId, found.value).left()
            is Either.Right -> found.value?.right() ?: clientNotFound().left()
        }

    /**
     * 提示されたclient_idのクライアントが存在しない場合の失敗情報
     * client_idの総当たりにあたるので、推測の試行として数える
     * DBを読めなかった場合はこれを使わず、clientLookupFailedで返すこと
     *
     * @return 認証エラー
     */
    private fun clientNotFound(): ClientAuthFailure =
        ClientAuthFailure(OAuthErrorCode.INVALID_CLIENT, "Client not found", ClientAuthFailureKind.CREDENTIALS_REJECTED)

    /**
     * クライアントをDBから読めなかった場合の失敗情報
     *
     * 認証情報を照合していないので推測の試行としては数えず（NOT_VERIFIED）、server_errorの500で再試行を促す。
     * invalid_clientで返すと、DBの障害の間に届いたリクエストが全て推測として数えられ、正規のクライアントの接続元までブロックされる。
     * 管理者が障害に気づけるようSEVEREで記録する。シークレットは記録しない
     *
     * @param clientId 提示されたクライアントID
     * @param error DBの読み取りの失敗
     * @return 認証エラー
     */
    private fun clientLookupFailed(clientId: String, error: OAuthClientError): ClientAuthFailure {
        // client_idはリクエスト由来なので、改行などでログを偽装されないよう無害化してから出力する
        get<MineAuth>().logger.severe(
            "Failed to read the OAuth client for client authentication: client_id=${sanitizeErrorDescription(clientId)}, error=$error"
        )
        return ClientAuthFailure(OAuthErrorCode.SERVER_ERROR, "The client could not be looked up. Retry later")
    }

    /**
     * Argon2Gateの許可を得てから、クライアントシークレットを照合する
     * DBの読み込みを終えてから呼ぶので、許可を持つのはハッシュの計算の間だけになる
     *
     * @param clientData 照合するConfidentialクライアント
     * @param clientSecret 提示されたシークレット
     * @param source リクエストの接続元（接続元ごとの検証の枠を割り当てるキー）
     * @return 一致した場合はRight。一致しない場合と、許可を得られず照合しなかった場合は種類の異なる失敗情報
     */
    private suspend fun verifyClientSecret(
        clientData: ClientData.ConfidentialClientData,
        clientSecret: String,
        source: ClientIpKey
    ): Either<ClientAuthFailure, Unit> {
        // ゲートはプロセス全体で1つなので、保持せず呼び出しごとにKoinから取得する
        // 1つの接続元が同時に待機・実行できる検証は限られるので、大量の同時リクエストで他の接続元を待たせない
        val matched = get<Argon2Gate>().withPermit(source) { clientData.verifySecret(clientSecret) }.getOrElse {
            return ClientAuthFailure(
                OAuthErrorCode.TEMPORARILY_UNAVAILABLE,
                "The server is busy. Retry later",
                ClientAuthFailureKind.VERIFIER_BUSY
            ).left()
        }
        if (!matched) {
            return ClientAuthFailure(
                OAuthErrorCode.INVALID_CLIENT,
                "Client authentication failed",
                ClientAuthFailureKind.CREDENTIALS_REJECTED
            ).left()
        }
        return Unit.right()
    }
}