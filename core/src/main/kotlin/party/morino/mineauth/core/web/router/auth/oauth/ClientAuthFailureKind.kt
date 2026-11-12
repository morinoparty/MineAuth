package party.morino.mineauth.core.web.router.auth.oauth

/**
 * クライアント認証の失敗の種類
 * メッセージの文字列で判定すると文言の変更で数え方が変わってしまうので、種類を値として持たせる
 */
enum class ClientAuthFailureKind {
    // 認証情報を照合する前に断った（形式の誤り、重複したパラメータ、シークレットの欠如、クライアント種別の不一致、
    // DBの障害でクライアントを読めなかった場合のserver_error）
    // 何も推測していないので、バックオフには数えない
    NOT_VERIFIED,

    // 提示されたclient_idが存在しないことを確認した、またはシークレットを照合して一致しなかった
    // 認証情報の推測にあたるので、バックオフに数える
    CREDENTIALS_REJECTED,

    // Argon2の同時実行数が上限に達していて、シークレットを照合しなかった
    // 認証の失敗ではないので数えず、503で一時的に処理できないことを返す
    VERIFIER_BUSY
}
