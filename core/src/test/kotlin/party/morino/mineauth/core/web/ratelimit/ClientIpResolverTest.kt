package party.morino.mineauth.core.web.ratelimit

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ClientIpResolverとIPアドレスの解析のテスト
 * X-Forwarded-Forの偽装で別のクライアントになりすませないこと、解析で名前解決をしないことを確認する
 */
class ClientIpResolverTest {
    // RateLimitConfigの既定値と同じ範囲
    private val trustedProxies = listOf("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")
        .map { assertNotNull(CidrRange.parse(it)) }

    private fun resolve(peer: String, forwardedFor: String? = null): String =
        ClientIpResolver.resolve(peer, forwardedFor, trustedProxies).value

    private fun range(text: String): CidrRange = assertNotNull(CidrRange.parse(text))

    private fun address(text: String): IpAddress = assertNotNull(IpAddress.parse(text))

    @Nested
    @DisplayName("Resolving the client")
    inner class ResolvingTest {

        @Test
        @DisplayName("Ignores the header from an untrusted peer")
        fun untrustedPeerIgnoresHeader() {
            assertEquals("203.0.113.9", resolve("203.0.113.9", "198.51.100.1"))
        }

        @Test
        @DisplayName("Walks from right to left skipping trusted entries")
        fun walksRightToLeft() {
            assertEquals("203.0.113.7", resolve("127.0.0.1", "198.51.100.1, 203.0.113.7, 10.0.0.2,192.168.1.1"))
        }
    }

    @Nested
    @DisplayName("Normalizing IPv6")
    inner class Ipv6Test {

        @Test
        @DisplayName("Keys IPv6 addresses by their /64")
        fun keysByPrefix() {
            assertEquals("2001:db8:1:2::/64", resolve("2001:db8:1:2:3:4:5:6"))
            assertEquals("2001:db8:1:2::/64", resolve("2001:DB8:1:2:ffff::1"))
            assertEquals("2001:db8:1:3::/64", resolve("2001:db8:1:3::1"))
        }
    }

    @Nested
    @DisplayName("Parsing literals")
    inner class ParsingTest {

        @Test
        @DisplayName("Never resolves a host name")
        fun neverResolvesNames() {
            // localhostはDNSが無くてもループバックに解決できるので、解決していればnullにならない
            assertNull(IpAddress.parse("localhost"))
            assertNull(IpAddress.parse("example.com"))
            assertNull(CidrRange.parse("localhost/8"))
            // 16進数字だけのホスト名もIPv6として扱わない
            assertNull(IpAddress.parse("cafe.example"))
            assertNull(IpAddress.parse("cafe"))
            // InetAddressがリテラルと判定しない先頭文字は、コロンを含んでいても渡さない
            assertNull(IpAddress.parse(".:1"))
            assertNull(IpAddress.parse("[.:cafe.example]"))
        }
    }

    @Nested
    @DisplayName("CIDR ranges")
    inner class CidrTest {

        @Test
        @DisplayName("Matches ranges that end inside an octet")
        fun matchesPartialOctets() {
            val ipv4 = range("172.16.0.0/12")
            assertTrue(address("172.31.255.255") in ipv4)
            assertFalse(address("172.32.0.0") in ipv4)

            val ipv6 = range("fc00::/7")
            assertTrue(address("fd12::1") in ipv6)
            assertFalse(address("fe00::1") in ipv6)
        }
    }
}
