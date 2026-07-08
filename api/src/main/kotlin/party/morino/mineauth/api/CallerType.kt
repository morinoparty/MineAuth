package party.morino.mineauth.api

/**
 * エンドポイントを呼び出すトークンの種別
 */
enum class CallerType {
    /** OAuth2フローで発行されたプレイヤーのユーザートークン */
    USER,

    /** 管理者がコマンドで発行したサービスアカウントトークン */
    SERVICE
}
