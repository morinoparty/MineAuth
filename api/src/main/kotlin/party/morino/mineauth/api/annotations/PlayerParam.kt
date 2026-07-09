package party.morino.mineauth.api.annotations

import party.morino.mineauth.api.PlayerAccess

/**
 * パスセグメントからプレイヤーを解決して受け取るパラメータを定義するアノテーション
 * バインドできる型は`OfflinePlayer`のみ。`@Authenticated`エンドポイントでのみ使用可能。
 *
 * パスセグメントの値として以下を受け付ける:
 * - `"me"` — 認証ユーザー自身（ユーザートークンのみ）
 * - UUID文字列 — 直接解決
 * - プレイヤー名 — Bukkit経由で解決
 *
 * ```kotlin
 * @Get("/balance/{player}")
 * @Authenticated(callers = [CallerType.USER, CallerType.SERVICE])
 * suspend fun getBalance(@PlayerParam("player") player: OfflinePlayer): BalanceData
 * ```
 *
 * @property value 解決に使用するパスセグメント名（パスに`{セグメント名}`が存在しないと登録時エラー）
 * @property access 対象プレイヤーへのアクセスポリシー（デフォルト: 自分自身またはサービストークン）
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PlayerParam(
    val value: String = "player",
    val access: PlayerAccess = PlayerAccess.SELF_OR_SERVICE
)
