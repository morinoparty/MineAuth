package party.morino.mineauth.core.model

/**
 * OAuthクライアントIDを表すValue Class
 * 型安全性を確保し、コマンドパーサーでの使用を可能にする
 */
@JvmInline
value class ClientId(val value: String)
