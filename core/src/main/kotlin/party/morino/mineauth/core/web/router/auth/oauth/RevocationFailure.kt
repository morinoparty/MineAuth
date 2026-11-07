package party.morino.mineauth.core.web.router.auth.oauth

/**
 * トークン失効の登録に失敗した情報
 * 失効を記録できなかった事実を呼び出し元へ伝え、成功として応答しないようにする
 *
 * @param reason ログに記録する失敗理由（トークン本体は含めない）
 * @param cause 想定外の例外による失敗の場合はその例外（調査のためログへ出力する）
 */
data class RevocationFailure(
    val reason: String,
    val cause: Throwable? = null
)
