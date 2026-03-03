package party.morino.mineauth.api.annotations

/**
 * パスパラメータ {player} からプレイヤーを解決するアノテーション
 *
 * 以下の形式を受け付ける:
 * - "me" → 認証ユーザー自身のUUIDから解決
 * - UUID文字列 → Bukkit.getOfflinePlayer(UUID) で直接解決
 * - プレイヤー名 → Bukkit.getOfflinePlayer(name) で解決
 *
 * アクセス制御:
 * - ユーザートークン: 自分自身のデータのみアクセス可能
 * - サービストークン: 任意のプレイヤーにアクセス可能
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class TargetPlayer
