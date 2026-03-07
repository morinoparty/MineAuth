package party.morino.mineauth.core.web.router.auth.oauth

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
    ROLES("roles");

    companion object {
        // 許可されたスコープ値のセット（高速なルックアップ用）
        private val ALLOWED_VALUES = entries.map { it.value }.toSet()

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
    }
}
