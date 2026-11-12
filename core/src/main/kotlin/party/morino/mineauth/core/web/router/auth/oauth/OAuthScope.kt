package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * OAuth2.0/OIDCで許可されるスコープの定義
 * RFC 6749 Section 3.3 準拠
 *
 * @property value スコープ文字列（スペース区切りのスコープリスト内で使用される値）
 */
enum class OAuthScope(val value: String) {
    // OIDC Core: sub（Subject Identifier）を返す
    OPENID("openid"),
    // OIDC Core Section 5.4: name, preferred_username, pictureを返す
    PROFILE("profile"),
    // OIDC Core Section 5.4: email, email_verifiedを返す
    EMAIL("email"),
    // カスタムスコープ: プラグインAPIへのアクセスを許可する
    PLUGIN("plugin"),
    // カスタムスコープ: LuckPermsのロール情報を返す
    ROLES("roles"),
    // OIDC Core Section 11: リフレッシュトークンの要求を表す
    // MineAuthは要求の有無にかかわらずリフレッシュトークンを発行するので、受理するだけで追加のクレームは返さない
    OFFLINE_ACCESS("offline_access");

    companion object {
        // スコープ値からenumを引くためのマップ（許可リストの判定も兼ねる）
        private val BY_VALUE = entries.associateBy { it.value }

        // 許可されたスコープ値のセット（高速なルックアップ用）
        private val ALLOWED_VALUES = BY_VALUE.keys

        /**
         * スコープ文字列をパースしてリストに変換する
         * 空白で分割し、空要素を除去する
         *
         * @param scopeString スペース区切りのスコープ文字列
         * @return スコープ値のリスト
         */
        fun parse(scopeString: String): List<String> {
            return scopeString.split(" ").filter { it.isNotBlank() }
        }

        /**
         * スコープ文字列（スペース区切り）を検証する
         * RFC 6749 Section 3.3: 各スコープ値が許可リストに含まれているか確認
         *
         * @param scopeString スペース区切りのスコープ文字列
         * @return 無効なスコープ値のリスト（空リストなら全て有効）
         */
        fun findInvalidScopes(scopeString: String): List<String> {
            return parse(scopeString).filter { it !in ALLOWED_VALUES }
        }

        /**
         * スコープ文字列が有効かどうかを検証する
         * 空のスコープ文字列はmalformedとみなしfalseを返す
         *
         * @param scopeString スペース区切りのスコープ文字列
         * @return 全てのスコープが有効で、かつ1つ以上のスコープが含まれている場合true
         */
        fun isValid(scopeString: String): Boolean {
            val scopes = parse(scopeString)
            // RFC 6749 Section 3.3: スコープが空はmalformed
            if (scopes.isEmpty()) return false
            return scopes.all { it in ALLOWED_VALUES }
        }

        /**
         * スコープ文字列を正規化する
         * 余分な空白を除去し、単一スペース区切りに変換する
         *
         * @param scopeString スペース区切りのスコープ文字列
         * @return 正規化されたスコープ文字列
         */
        fun normalize(scopeString: String): String {
            return parse(scopeString).joinToString(" ")
        }

        /**
         * 要求されたスコープから、実際に付与するスコープを決める
         *
         * openidを含むリクエストはOIDCのリクエストなので、OIDC Core 1.0 Section 3.1.2.1 に従い
         * 認識できない値を無視する。この場合はopenid自体が必ず残るため失敗しない。
         * openidを含まない素のOAuthリクエストは、RFC 6749 Section 3.3 で認められている厳格な扱いを保ち、
         * 認識できない値が1つでもあれば失敗させる。
         *
         * 付与結果はトークンレスポンスのscopeとして返るので、リクエストの順序を保ったまま重複を除く
         *
         * @param requested スペース区切りのスコープ文字列
         * @return 付与するスコープ、または付与できない理由
         */
        fun resolveGranted(requested: String): Either<InvalidScope, Set<OAuthScope>> {
            val values = parse(requested)
            // RFC 6749 Section 3.3: 空のスコープは付与するものが無いので失敗にする
            if (values.isEmpty()) return InvalidScope(emptyList()).left()
            val unknownValues = findInvalidScopes(requested)
            if (OPENID.value !in values && unknownValues.isNotEmpty()) return InvalidScope(unknownValues).left()
            return values.mapNotNull(BY_VALUE::get).toSet().right()
        }

        /**
         * 付与したスコープをスペース区切りの文字列に変換する
         *
         * @param scopes 付与したスコープ
         * @return スペース区切りのスコープ文字列
         */
        fun format(scopes: Set<OAuthScope>): String = scopes.joinToString(" ") { it.value }
    }
}
