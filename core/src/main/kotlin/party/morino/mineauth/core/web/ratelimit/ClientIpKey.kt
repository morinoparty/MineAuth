package party.morino.mineauth.core.web.ratelimit

/**
 * クライアントを数えるときに使う、正規化済みのキー
 * ユーザー名やclient_idのキーと取り違えないよう、文字列のまま渡さず型で区別する
 *
 * @property value IPv4アドレス、IPv6の/64、またはIPリテラルでない接続元の文字列
 */
data class ClientIpKey(val value: String)
