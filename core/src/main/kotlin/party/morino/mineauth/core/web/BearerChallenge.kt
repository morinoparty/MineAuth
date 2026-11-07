package party.morino.mineauth.core.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authentication
import io.ktor.server.request.authorization
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.plugin.route.ErrorResponse
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bearerトークンで保護されたリソースの認証・認可エラー応答を組み立てるヘルパー
 *
 * RFC 6750 Section 3 はアクセスを許可できないリクエストへのWWW-Authenticateヘッダーを必須としている。
 * OAuth/OIDCクライアントライブラリはボディではなくこのヘッダーを見て再認証やスコープ追加の要否を判断するため、
 * ヘッダーとエラーエンベロープ（error/code）を必ず同時に返せるよう、応答処理をここに集約する。
 */
object BearerChallenge : KoinComponent {

    // RFC 6750で定義された認証スキーム名
    private const val BEARER_SCHEME = "Bearer"

    // error_descriptionの最大長
    // ボディのメッセージにはハンドラーが書いた任意の長さの文字列も含まれ、ヘッダーのサイズ上限を超えると応答自体が失敗する
    private const val MAX_ERROR_DESCRIPTION_LENGTH = 256

    // validateブロックが想定外の例外で失敗したときにスパン属性 mineauth.auth.result へ記録する値
    private const val AUTH_RESULT_VALIDATION_ERROR = "validation_error"

    /**
     * RFC 6750 Section 3.1 のerror属性に使うコード
     *
     * @property value ヘッダーに出力する文字列
     */
    enum class ErrorCode(val value: String) {
        // トークンが期限切れ、失効済み、または不正
        INVALID_TOKEN("invalid_token"),

        // トークンは有効だがこのリソースへのアクセスを許可しない
        INSUFFICIENT_SCOPE("insufficient_scope"),
    }

    /**
     * WWW-Authenticateヘッダーの値を組み立てる
     *
     * 値はヘッダーインジェクションと文法違反を防ぐために属性ごとの許可文字へ揃える。
     * 除去の結果が空になったerror_descriptionとscopeは属性ごと省略し、長すぎるerror_descriptionは切り詰める。
     *
     * @param realm 保護領域の名前（quoted-stringとしてエスケープする）
     * @param error エラーコード（資格情報が無いリクエストではnull）
     * @param errorDescription 人間向けの説明
     * @param scope アクセスに必要なスコープ（スペース区切り）
     * @return WWW-Authenticateヘッダーの値
     */
    fun headerValue(
        realm: String,
        error: ErrorCode? = null,
        errorDescription: String? = null,
        scope: String? = null,
    ): String {
        // 属性名と、引用符で囲む前の値の組を出力順に並べる
        val attributes = buildList {
            add("realm" to escapeQuotedString(realm))
            error?.let { add("error" to it.value) }
            errorDescription?.let(::keepBearerParamChars)?.takeIf { it.isNotBlank() }
                ?.let { add("error_description" to it.take(MAX_ERROR_DESCRIPTION_LENGTH)) }
            scope?.let(::keepBearerParamChars)?.takeIf { it.isNotBlank() }
                ?.let { add("scope" to it) }
        }
        return "$BEARER_SCHEME " + attributes.joinToString(", ") { (name, value) -> "$name=\"$value\"" }
    }

    /**
     * Bearerトークンが提示されなかったリクエストかを判定する
     *
     * RFC 6750 Section 3.1 はサポート外の認証方式での試行も認証情報なしと同じ扱いにしている。
     * Ktorはこの場合をInvalidCredentialsとして記録するため、Authorizationヘッダーの方式も確認する。
     * 判定できない場合は無効なトークンとして扱えるよう、失敗原因はすべてがNoCredentialsのときだけ採用する。
     *
     * @param failures AuthenticationContextに記録された失敗原因
     * @param authorization Authorizationヘッダーの値
     * @return Bearerトークンが提示されていない場合はtrue
     */
    fun isCredentialMissing(failures: List<AuthenticationFailedCause>, authorization: String?): Boolean {
        val usesOtherScheme = authorization != null &&
            !authorization.substringBefore(' ').equals(BEARER_SCHEME, ignoreCase = true)
        return usesOtherScheme || (failures.isNotEmpty() && failures.all { it == AuthenticationFailedCause.NoCredentials })
    }

    /**
     * WWW-Authenticateヘッダーを設定してエラーレスポンスを返す
     *
     * error_descriptionにはボディのメッセージを使う。
     * RFC 6750 Section 3.1 により、errorが無い応答にはエラー情報を付けない。
     *
     * @param call 応答対象のApplicationCall
     * @param status HTTPステータス
     * @param body レスポンスボディ
     * @param error エラーコード（資格情報が無いリクエストではnull）
     * @param scope アクセスに必要なスコープ（公開してよい値のみ渡す）
     */
    suspend fun respond(
        call: ApplicationCall,
        status: HttpStatusCode,
        body: ErrorResponse,
        error: ErrorCode? = null,
        scope: String? = null,
    ) {
        // /mineauth reload後のrealmに追従するため、呼び出しごとにKoinから取得する
        val realm = get<JWTConfigData>().realm
        val description = body.error.takeIf { error != null }
        call.response.header(HttpHeaders.WWWAuthenticate, headerValue(realm, error, description, scope))
        call.respond(status, body)
    }

    /**
     * jwtプロバイダのchallengeから呼び出し、失敗原因に応じた401レスポンスを返す
     *
     * Ktorのchallengeラムダは原因を引数で受け取らないため、AuthenticationContextの記録を参照する。
     *
     * @param call 応答対象のApplicationCall
     * @param invalidTokenMessage トークンが無効な場合のメッセージ
     */
    suspend fun respondProviderFailure(call: ApplicationCall, invalidTokenMessage: String) {
        if (isCredentialMissing(call.authentication.allFailures, call.request.authorization())) {
            // RFC 6750 Section 3.1: 資格情報が無いリクエストにはerror属性を付けない
            respond(
                call,
                HttpStatusCode.Unauthorized,
                ErrorResponse("Authentication required", code = "authentication_required"),
            )
        } else {
            respond(
                call,
                HttpStatusCode.Unauthorized,
                ErrorResponse(invalidTokenMessage, code = ErrorCode.INVALID_TOKEN.value),
                ErrorCode.INVALID_TOKEN,
            )
        }
    }

    /**
     * jwtプロバイダのvalidateブロックの処理を実行し、想定外の例外を認証失敗に変換する
     *
     * validateから例外が漏れると、Ktorはchallengeを呼ばずにヘッダーも本文も無い401を返す（RFC 9110 Section 15.5.2 違反）。
     * nullを返せば通常のchallengeがinvalid_tokenで応答する。検証できなかったトークンを受け入れないよう、必ず拒否側に倒す。
     *
     * ログのメッセージは固定文字列とする。validateブロックにはトークン本体が渡されないので、例外にも含まれない。
     *
     * @param span 検証の過程を記録しているスパン
     * @param logger 失敗を記録するロガー
     * @param block validateブロックの処理
     * @return blockの戻り値。想定外の例外が発生した場合はnull
     */
    suspend fun <T : Any> failClosed(span: Span, logger: Logger, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        // コルーチンのキャンセルは認証失敗ではないので再送出する
        throw e
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "Unexpected failure while validating a bearer token. The request was rejected", e)
        // ここで捕捉するとwithSpanは例外を検知できないので、同じ内容をスパンへ記録する
        span.setAttribute(TelemetryAttributes.AUTH_RESULT, AUTH_RESULT_VALIDATION_ERROR)
        span.recordException(e)
        span.setStatus(StatusCode.ERROR)
        null
    }

    /**
     * RFC 9110 Section 5.6.4 のquoted-stringとして安全な文字列に変換する
     *
     * 設定値にCR/LFなどが混入してもヘッダーを壊さないよう、印字可能なASCII以外は除去する。
     */
    private fun escapeQuotedString(value: String): String =
        value.filter { it in ' '..'~' }
            // 先にバックスラッシュを処理し、引用符用に追加したエスケープを二重に変換しない
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

    /**
     * RFC 6750 Section 3 がerror_descriptionとscopeに許可する文字だけを残す
     *
     * 許可範囲（%x20-21 / %x23-5B / %x5D-7E）に引用符とバックスラッシュは含まれず、
     * quoted-pairでのエスケープも使えないため除去する。
     */
    private fun keepBearerParamChars(value: String): String =
        value.filter { it in ' '..'~' && it != '"' && it != '\\' }
}
