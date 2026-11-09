package party.morino.mineauth.core.web.router.auth.oauth

/**
 * 要求されたスコープから付与できるスコープを決められなかったことを表す
 * RFC 6749 Section 3.3 の invalid_scope に対応する
 *
 * @property unknownValues 認識できなかったスコープ値（スコープ自体が空の場合は空リスト）
 */
data class InvalidScope(val unknownValues: List<String>)
