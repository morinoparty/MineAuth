package party.morino.mineauth.core.model

/**
 * サービスアカウント名を表すValue Class
 * 型安全性を確保し、コマンドパーサーでの使用を可能にする
 */
@JvmInline
value class ServiceName(val value: String)
