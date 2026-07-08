package party.morino.mineauth.api

/**
 * `@PlayerParam`で解決される対象プレイヤーへのアクセスポリシー
 */
enum class PlayerAccess {
    /** ユーザートークンで自分自身のみアクセス可能（サービストークンは拒否） */
    SELF_ONLY,

    /** ユーザートークンは自分自身のみ、サービストークンは任意のプレイヤーにアクセス可能 */
    SELF_OR_SERVICE,

    /** 認証済みであれば任意のプレイヤーにアクセス可能 */
    ANY_AUTHENTICATED
}
