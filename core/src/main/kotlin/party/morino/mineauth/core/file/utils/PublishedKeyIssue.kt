package party.morino.mineauth.core.file.utils

/**
 * 公開用ファイル（certificate.pem / jwks.json）が署名鍵と揃っていない理由
 *
 * @property description ログに出す説明
 */
enum class PublishedKeyIssue(val description: String) {
    // 初回起動時や、管理者が削除した場合
    CERTIFICATE_MISSING("certificate.pem does not exist"),

    // 書き込み途中の破損や手作業での編集ミス
    CERTIFICATE_UNREADABLE("certificate.pem cannot be parsed"),

    // privateKey.pem / publicKey.pem の差し替え後に残った古い証明書
    CERTIFICATE_KEY_DIFFERS("certificate.pem holds a different public key"),

    // 初回起動時や、管理者が削除した場合
    JWKS_MISSING("jwks.json does not exist"),

    // 書き込み途中の破損や手作業での編集ミス
    JWKS_UNREADABLE("jwks.json cannot be parsed"),

    // jwt.keyIdの変更後に残った古いJWK Set（kidを一意に特定できない場合も含む）
    JWKS_KEY_ID_NOT_FOUND("jwks.json has no single RSA key with the configured jwt.keyId"),

    // privateKey.pem / publicKey.pem の差し替え後に残った古いJWK Set
    JWKS_KEY_DIFFERS("jwks.json holds a different public key under the configured jwt.keyId"),
}
