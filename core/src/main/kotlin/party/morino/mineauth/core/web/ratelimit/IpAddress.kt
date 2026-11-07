package party.morino.mineauth.core.web.ratelimit

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 解析済みのIPアドレス（IPv4は4バイト、IPv6は16バイト）
 * IPv4射影アドレスはIPv4として扱うので、同じ接続元が表記によって別のアドレスになることはない
 *
 * @property octets アドレスのバイト列
 */
class IpAddress private constructor(private val octets: ByteArray) {
    // アドレス全体のビット数（CIDRのプレフィックス長の上限）
    val bitLength: Int
        get() = octets.size * Byte.SIZE_BITS

    // IPv4アドレスかどうか
    val isIpv4: Boolean
        get() = octets.size == IPV4_OCTETS

    /**
     * ネットワークアドレスと先頭のビット列が一致するかを判定する
     *
     * @param network ネットワークアドレス
     * @param prefixLength 比較するビット数
     * @return 同じアドレスファミリーで先頭が一致する場合はtrue
     */
    fun hasPrefixOf(network: IpAddress, prefixLength: Int): Boolean {
        if (octets.size != network.octets.size) return false

        val wholeOctets = prefixLength / Byte.SIZE_BITS
        if (!(0 until wholeOctets).all { octets[it] == network.octets[it] }) return false

        // /12 や /7 のようにバイトの途中で終わる場合は、残りのビットだけをマスクして比較する
        val remainingBits = prefixLength % Byte.SIZE_BITS
        if (remainingBits == 0) return true
        val mask = (OCTET_MASK shl (Byte.SIZE_BITS - remainingBits)) and OCTET_MASK
        return (octets[wholeOctets].toInt() and mask) == (network.octets[wholeOctets].toInt() and mask)
    }

    /**
     * 流量制限で数えるときのキーに変換する
     * IPv6は1人が/64を丸ごと持っているのが普通で、アドレス単位で数えるといくらでも回避できるので/64にまとめる
     *
     * @return 正規化済みのキー
     */
    fun toKey(): ClientIpKey =
        if (isIpv4) {
            ClientIpKey(octets.joinToString(".") { (it.toInt() and OCTET_MASK).toString() })
        } else {
            // 上位64ビットを4つの16ビット値で表記する
            val hextets = (0 until IPV6_NETWORK_OCTETS step 2).joinToString(":") { index ->
                val high = octets[index].toInt() and OCTET_MASK
                val low = octets[index + 1].toInt() and OCTET_MASK
                ((high shl Byte.SIZE_BITS) or low).toString(HEX_RADIX)
            }
            ClientIpKey("$hextets::/64")
        }

    companion object {
        private const val IPV4_OCTETS = 4
        private const val MAX_IPV4_OCTET = 255
        private const val OCTET_MASK = 0xFF
        private const val IPV6_NETWORK_OCTETS = 8
        private const val HEX_RADIX = 16

        // 10進の4区切りのみ。先頭ゼロは8進数と解釈する実装があり、見る側によって別のアドレスになるので受け付けない
        private val IPV4_LITERAL = Regex("""(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}""")

        // 角括弧とゾーンを除いたIPv6の表記。末尾にIPv4形式を含む場合があるのでドットも許可する
        // 先頭は16進数字かコロンに限る。InetAddressは先頭の文字でリテラルかどうかを判定し、それ以外は名前解決してしまう
        private val IPV6_LITERAL = Regex("""([0-9a-fA-F:][0-9a-fA-F:.]*)(%[\w.~-]+)?""")

        /**
         * IPリテラルを解析する
         * リクエスト由来の値を扱うので名前解決は絶対に行わない。ホスト名はIPアドレスではないものとして扱う
         *
         * @param text IPv4アドレス、または角括弧とゾーンが付いていてもよいIPv6アドレス
         * @return 解析結果。IPリテラルでない場合はnull
         */
        fun parse(text: String): IpAddress? = parseIpv4(text) ?: parseIpv6(text)

        /**
         * IPv4リテラルを解析する
         * InetAddressは "1.2.3" のような省略形も受け入れるので、自前で厳密に解釈する
         *
         * @param text 解析する文字列
         * @return 解析結果。IPv4リテラルでない場合はnull
         */
        private fun parseIpv4(text: String): IpAddress? {
            if (!IPV4_LITERAL.matches(text)) return null
            val octets = text.split('.').map { it.toInt() }
            if (octets.any { it > MAX_IPV4_OCTET }) return null
            return IpAddress(ByteArray(IPV4_OCTETS) { octets[it].toByte() })
        }

        /**
         * IPv6リテラルを解析する
         * 16進数字かコロンで始まりコロンを含む文字列を、InetAddressはリテラルとして解釈し、不正な場合も名前解決せずに例外を投げる。
         * ゾーンを渡すとネットワークインターフェースを参照するので、取り除いてから渡す
         *
         * @param text 解析する文字列
         * @return 解析結果（IPv4射影アドレスは4バイトになる）。IPv6リテラルでない場合はnull
         */
        private fun parseIpv6(text: String): IpAddress? {
            val unbracketed = if (text.startsWith('[') && text.endsWith(']')) text.substring(1, text.length - 1) else text
            val literal = IPV6_LITERAL.matchEntire(unbracketed)?.groupValues?.get(1) ?: return null
            if (':' !in literal) return null
            return try {
                IpAddress(InetAddress.getByName(literal).address)
            } catch (e: UnknownHostException) {
                // IPv6として不正な表記（名前解決は行われていない）
                null
            }
        }
    }
}
