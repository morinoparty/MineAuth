package party.morino.mineauth.core.web.components.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenID Connect UserInfo Response
 * OIDC Core Section 5.3.2 準拠
 *
 * スコープに応じて返却されるクレームが決定される：
 * - openid: sub（必須）
 * - profile: name, picture, preferred_username
 * - email: email, email_verified
 * - roles: roles（LuckPermsのグループ情報）
 *
 * @property sub Subject Identifier - ユーザーの一意識別子（UUID形式）
 * @property name ユーザーのフルネーム（Minecraftではプレイヤー名と同一）
 * @property picture ユーザーのプロフィール画像URL（profileスコープが必要）
 * @property preferredUsername OIDC標準のユーザー名（profileスコープが必要）
 * @property email ユーザーのメールアドレス（emailスコープが必要）
 * @property emailVerified メールアドレスの検証状態（常にfalse）
 * @property roles LuckPermsのグループ名一覧（rolesスコープが必要）
 */
@Serializable
data class UserInfoResponse(
    // openid スコープ: 必須 - Subject Identifier
    val sub: String,

    // profile スコープ: ユーザーのフルネーム（Minecraftではプレイヤー名と同一）
    val name: String? = null,

    // profile スコープ: プロフィール画像URL
    val picture: String? = null,

    // profile スコープ: OIDC標準のユーザー名
    @SerialName("preferred_username")
    val preferredUsername: String? = null,

    // email スコープ: メールアドレス
    val email: String? = null,

    // email スコープ: メールアドレス検証状態（常にfalse）
    @SerialName("email_verified")
    val emailVerified: Boolean? = null,

    // roles スコープ: LuckPermsのグループ名一覧
    val roles: List<String>? = null
) {
    companion object {
        // Crafthead API - Minecraftスキンからアバター画像を取得
        internal const val AVATAR_BASE_URL = "https://crafthead.net/avatar/"

        /**
         * スコープに基づいてUserInfoResponseを構築する
         *
         * @param sub Subject Identifier（UUID文字列）
         * @param username ユーザー名
         * @param scopes スコープのリスト
         * @param email 生成されたメールアドレス（emailFormatが設定されている場合）
         * @param roles LuckPermsから取得したグループ名一覧（rolesスコープが必要）
         * @return スコープに応じたUserInfoResponse
         */
        fun fromScopes(
            sub: String,
            username: String,
            scopes: List<String>,
            email: String? = null,
            roles: List<String>? = null
        ): UserInfoResponse {
            // profileスコープが含まれる場合のみpicture, preferred_usernameを返す
            val hasProfileScope = scopes.contains("profile")
            // emailスコープが含まれ、かつemailが提供されている場合のみemailを返す
            val hasEmailScope = scopes.contains("email") && email != null
            // rolesスコープが含まれ、かつrolesが提供されている場合のみrolesを返す
            val hasRolesScope = scopes.contains("roles") && roles != null

            return UserInfoResponse(
                sub = sub,
                name = if (hasProfileScope) username else null,
                picture = if (hasProfileScope) "$AVATAR_BASE_URL$sub" else null,
                preferredUsername = if (hasProfileScope) username else null,
                email = if (hasEmailScope) email else null,
                emailVerified = if (hasEmailScope) false else null,
                roles = if (hasRolesScope) roles else null
            )
        }

        /**
         * emailFormatからメールアドレスを生成する
         *
         * @param emailFormat メールフォーマット（例: "<uuid>+<username>@example.com"）
         * @param uuid プレイヤーのUUID
         * @param username プレイヤー名
         * @return 生成されたメールアドレス
         */
        fun generateEmail(emailFormat: String, uuid: String, username: String): String {
            return emailFormat
                .replace("<uuid>", uuid)
                .replace("<username>", username)
        }
    }
}
