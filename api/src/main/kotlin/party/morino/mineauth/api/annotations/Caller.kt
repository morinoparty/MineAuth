package party.morino.mineauth.api.annotations

/**
 * リクエスト元の認証主体（[party.morino.mineauth.api.auth.Principal]）を受け取るパラメータを定義するアノテーション
 *
 * バインドできる型:
 * - `Principal` — ユーザー・サービスどちらのトークンでも受け取れる
 * - `Principal.User` — ユーザートークンのみ（`callers = [CallerType.USER]`が必要）
 * - `Principal.Service` — サービストークンのみ（`callers = [CallerType.SERVICE]`が必要）
 *
 * null許容性のルール:
 * - `@Authenticated`エンドポイント: non-nullable（認証済みが保証されるため）
 * - `@Public`エンドポイント: nullable（未認証の場合はnullが渡される）
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Caller
