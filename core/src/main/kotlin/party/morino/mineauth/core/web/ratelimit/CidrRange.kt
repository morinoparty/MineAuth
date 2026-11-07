package party.morino.mineauth.core.web.ratelimit

/**
 * IPアドレスの範囲（CIDR表記）
 *
 * @property network ネットワークアドレス
 * @property prefixLength ネットワーク部のビット数
 */
class CidrRange private constructor(
    private val network: IpAddress,
    private val prefixLength: Int
) {
    /**
     * アドレスがこの範囲に含まれるかを判定する
     *
     * @param address 判定するアドレス
     * @return 含まれる場合はtrue
     */
    operator fun contains(address: IpAddress): Boolean = address.hasPrefixOf(network, prefixLength)

    companion object {
        // プレフィックス長の表記（"+8" や "-1" のようにtoIntOrNullが受け入れる符号付きの値は認めない）
        private val PREFIX_LENGTH = Regex("""\d{1,3}""")

        // IPv4射影アドレス（::ffff:0:0/96）の、IPv4部分より前のビット数
        private const val IPV4_MAPPED_PREFIX_BITS = 96

        /**
         * CIDR表記を解析する
         * プレフィックス長を省略した値は、そのアドレス1つだけの範囲として扱う
         *
         * @param text "10.0.0.0/8" や "fc00::/7"、"203.0.113.5" のような文字列
         * @return 解析結果。範囲として解釈できない場合はnull
         */
        fun parse(text: String): CidrRange? {
            val entry = text.trim()
            val addressText = entry.substringBefore('/')
            val address = IpAddress.parse(addressText) ?: return null
            if ('/' !in entry) return CidrRange(address, address.bitLength)

            val prefixText = entry.substringAfter('/')
            if (!PREFIX_LENGTH.matches(prefixText)) return null

            // IPv4射影の表記はIPv4に変換されるので、プレフィックス長もIPv4の値に読み替える
            val written = prefixText.toInt()
            val prefixLength = if (address.isIpv4 && ':' in addressText) written - IPV4_MAPPED_PREFIX_BITS else written
            return if (prefixLength in 0..address.bitLength) CidrRange(address, prefixLength) else null
        }
    }
}
