package party.morino.mineauth.core.web.components.plugin

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.AccessInfo
import party.morino.mineauth.api.RegisteredEndpoint

/**
 * アドオンが登録した1つのエンドポイントの公開情報
 *
 * @property method HTTPメソッド（GET / POST など）
 * @property path ベースパスを含む完全なパス（例: /api/v1/plugins/tickets/tickets）
 * @property access アクセス種別（PUBLIC / AUTHENTICATED）
 * @property permission 必要なパーミッションノード（認証必須かつ指定がある場合のみ）
 * @property callers 呼び出しを許可されたトークン種別（認証必須の場合のみ）
 */
@Serializable
data class RegisteredEndpointData(
    val method: String,
    val path: String,
    val access: String,
    val permission: String? = null,
    val callers: List<String>? = null,
) {
    companion object {
        /** 公開エンドポイントを表すaccess値 */
        const val ACCESS_PUBLIC = "PUBLIC"

        /** 認証必須エンドポイントを表すaccess値 */
        const val ACCESS_AUTHENTICATED = "AUTHENTICATED"

        /**
         * 登録済みエンドポイント情報からレスポンス用データを生成する
         *
         * @param endpoint MineAuthApi経由で登録されたエンドポイント
         * @return レスポンス用データ
         */
        fun from(endpoint: RegisteredEndpoint): RegisteredEndpointData = when (val access = endpoint.access) {
            is AccessInfo.Public -> RegisteredEndpointData(
                method = endpoint.httpMethod.name,
                path = endpoint.fullPath,
                access = ACCESS_PUBLIC,
            )

            is AccessInfo.Authenticated -> RegisteredEndpointData(
                method = endpoint.httpMethod.name,
                path = endpoint.fullPath,
                access = ACCESS_AUTHENTICATED,
                permission = access.permission,
                // 表示順を安定させるため enum の宣言順（USER, SERVICE）に並べる
                callers = access.callers.sortedBy { it.ordinal }.map { it.name },
            )
        }
    }
}
