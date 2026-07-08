package party.morino.mineauth.api.annotations

import party.morino.mineauth.api.CallerType

/**
 * 認証必須のエンドポイントであることを宣言するアノテーション
 *
 * 全てのエンドポイントは`@Public`または`@Authenticated`のどちらか一方を
 * 必ず宣言しなければならない（宣言がない場合は登録時にエラーとなる）。
 *
 * ```kotlin
 * @Get("/balance/{player}")
 * @Authenticated(permission = "vault.balance.get")
 * suspend fun getBalance(@PlayerParam player: OfflinePlayer): BalanceData
 * ```
 *
 * @property permission 必要なパーミッションノード（空文字の場合は認証のみでパーミッションチェックなし）。
 *   ユーザートークンに対してのみ評価される。プレイヤーがオフラインの場合、チェックは失敗する（403 player_offline）。
 * @property callers このエンドポイントを呼び出せるトークン種別。
 *   デフォルトはユーザートークンのみ。サービストークンを許可する場合は明示的に
 *   `callers = [CallerType.USER, CallerType.SERVICE]` を指定する必要がある。
 *   サービストークンは管理者が発行する信頼された資格情報であり、`permission`の評価対象外となる。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Authenticated(
    val permission: String = "",
    val callers: Array<CallerType> = [CallerType.USER]
)
