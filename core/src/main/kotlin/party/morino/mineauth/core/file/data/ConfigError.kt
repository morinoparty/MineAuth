package party.morino.mineauth.core.file.data

/**
 * 設定内容や鍵ファイルの誤りを表すエラー
 * messageはそのまま管理者向けのログに出すため、直し方が分かる英語で書く
 *
 * @property message 管理者向けのエラーメッセージ
 */
sealed class ConfigError(val message: String) {
    // URLとして解釈できない、またはスキームがなく絶対URLになっていない
    data class BaseUrlUnparsable(val baseUrl: String, val reason: String) : ConfigError(
        "server.baseUrl ($baseUrl) is not a valid absolute URL: $reason"
    )

    // ホスト名がない（"api.example.com" のようにスキームを省略した場合など）
    data class BaseUrlMissingHost(val baseUrl: String) : ConfigError(
        "server.baseUrl ($baseUrl) has no host. Use an absolute URL such as https://api.example.com"
    )

    // クエリ付き（issuerはクエリを持てず、全エンドポイントのURLの途中にも混入する）
    data class BaseUrlHasQuery(val baseUrl: String) : ConfigError(
        "server.baseUrl ($baseUrl) must not contain a query. " +
            "It is used as the OIDC issuer and as the prefix of every endpoint URL"
    )

    // フラグメント付き（issuerはフラグメントを持てず、全エンドポイントのURLの途中にも混入する）
    data class BaseUrlHasFragment(val baseUrl: String) : ConfigError(
        "server.baseUrl ($baseUrl) must not contain a fragment. " +
            "It is used as the OIDC issuer and as the prefix of every endpoint URL"
    )

    // 秘密鍵と公開鍵が別の鍵ペアのもの（署名を公開鍵で検証できない）
    data object SigningKeyPairMismatch : ConfigError(
        "generated/privateKey.pem and generated/publicKey.pem are not a key pair. " +
            "Tokens signed with this private key cannot be verified. Replace both files together"
    )
}
