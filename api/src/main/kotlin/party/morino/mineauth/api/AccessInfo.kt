package party.morino.mineauth.api

/**
 * エンドポイントのアクセス制御情報を表すsealed interface
 */
sealed interface AccessInfo {

    /** 認証不要の公開エンドポイント */
    data object Public : AccessInfo

    /**
     * 認証必須のエンドポイント
     *
     * @property permission 必要なパーミッションノード（nullの場合は認証のみ）
     * @property callers このエンドポイントを呼び出せるトークン種別
     */
    data class Authenticated(
        val permission: String?,
        val callers: Set<CallerType>
    ) : AccessInfo
}
