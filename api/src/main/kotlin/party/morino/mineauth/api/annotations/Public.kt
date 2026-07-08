package party.morino.mineauth.api.annotations

/**
 * 認証不要の公開エンドポイントであることを宣言するアノテーション
 *
 * 全てのエンドポイントは`@Public`または`@Authenticated`のどちらか一方を
 * 必ず宣言しなければならない（宣言がない場合は登録時にエラーとなる）。
 * これにより「アノテーションの付け忘れで意図せず公開される」事故を防ぐ。
 *
 * @property reason 公開する理由（任意。指定するとOpenAPIドキュメントに出力される）
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Public(val reason: String = "")
