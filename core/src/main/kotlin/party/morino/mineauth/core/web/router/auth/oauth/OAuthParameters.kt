package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.http.*

/**
 * OAuthリクエストのパラメータを1件読み取る
 *
 * `Parameters.get()` は空値を「存在する」と扱い、重複時は先頭の値を黙って採用する。
 * どちらも RFC 6749 Section 3.2 の要件に沿わないため、トークン系エンドポイントはこの関数を経由して読み取る。
 *
 * - 同じ名前が2回以上現れる場合は invalid_request（RFC 6749 Section 5.2）
 * - 値が空または空白のみの場合は省略されたものとして null を返す（RFC 6749 Section 3.2）
 *
 * @param name パラメータ名
 * @return パラメータの値（省略時はnull）、または読み取りエラー
 */
fun Parameters.readSingleParameter(name: String): Either<OAuthParameterFailure, String?> {
    val values = getAll(name).orEmpty()

    // 値の中身より先に重複を判定する（code=&code=abc のように片方が空でも重複として拒否するため）
    if (values.size > 1) {
        return OAuthParameterFailure(
            OAuthErrorCode.INVALID_REQUEST,
            "Parameter must not be repeated: $name"
        ).left()
    }

    return values.singleOrNull()?.takeIf { it.isNotBlank() }.right()
}
