package party.morino.mineauth.core.model

/**
 * サービスアカウント名を表すデータクラス
 * 型安全性を確保し、コマンドパーサーでの使用を可能にする
 * Note: value classはcloud-kotlin-coroutinesのsuspend関数でリフレクションエラーを起こすためdata classを使用
 */
data class ServiceName(val value: String)
