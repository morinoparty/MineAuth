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
        @DisplayName("Uses the peer when a trusted peer sends no header")
        fun trustedPeerWithoutHeader() {
            assertEquals("127.0.0.1", resolve("127.0.0.1"))
        }

        @Test
        @DisplayName("Never prefers a spoofed leftmost entry")
        fun ignoresSpoofedLeftmostEntry() {
            assertEquals("203.0.113.7", resolve("127.0.0.1", "198.51.100.1, 203.0.113.7"))
        }

        @Test
        @DisplayName("Walks from right to left skipping trusted entries")
        fun walksRightToLeft() {
            assertEquals("203.0.113.7", resolve("127.0.0.1", "198.51.100.1, 203.0.113.7, 10.0.0.2,192.168.1.1"))
        }

        @Test
        @DisplayName("Uses the peer when every entry is trusted")
        fun everyEntryTrusted() {
            assertEquals("127.0.0.1", resolve("127.0.0.1", "10.0.0.1, 192.168.0.2"))
        }

        @Test
        @DisplayName("Stops at a garbage entry and uses the peer")
        fun garbageEntryStopsWalk() {
            assertEquals("127.0.0.1", resolve("127.0.0.1", "203.0.113.7, unknown, 10.0.0.2"))
            assertEquals("127.0.0.1", resolve("127.0.0.1", ""))
        }

        @Test
        @DisplayName("Does not reach a garbage entry left of the client")
        fun garbageLeftOfClientIsNotReached() {
            assertEquals("203.0.113.7", resolve("127.0.0.1", "garbage, 203.0.113.7"))
        }

        @Test
        @DisplayName("Trusts no peer with an empty list")
        fun emptyTrustedProxies() {
            assertEquals("127.0.0.1", ClientIpResolver.resolve("127.0.0.1", "203.0.113.7", emptyList()).value)
        }

        @Test
        @DisplayName("Treats a peer that is not an IP literal as an opaque untrusted key")
        fun opaquePeer() {
            assertEquals("localhost", resolve("localhost", "203.0.113.7"))
            assertFalse(ClientIpResolver.isTrustedPeer("localhost", trustedProxies))
        }

        @Test
        @DisplayName("Reports whether the peer is trusted")
        fun reportsTrustedPeer() {
            assertTrue(ClientIpResolver.isTrustedPeer("192.168.0.1", trustedProxies))
            assertFalse(ClientIpResolver.isTrustedPeer("203.0.113.9", trustedProxies))
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

        @Test
        @DisplayName("Handles brackets and a zone suffix")
        fun handlesBracketsAndZone() {
            assertEquals("203.0.113.7", resolve("[::1]", "203.0.113.7"))
            assertEquals("fe80:0:0:0::/64", resolve("fe80::1%eth0"))
            assertEquals("fe80:0:0:0::/64", resolve("[fe80::1%eth0]"))
        }

        @Test
        @DisplayName("Treats an IPv4-mapped address as IPv4")
        fun treatsMappedAsIpv4() {
            assertEquals("203.0.113.7", resolve("::ffff:127.0.0.1", "203.0.113.7"))
            assertEquals("203.0.113.7", resolve("127.0.0.1", "::ffff:203.0.113.7"))
            assertEquals("203.0.113.7", resolve("::ffff:cb00:7107"))
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

        @Test
        @DisplayName("Rejects ambiguous and malformed IPv4")
        fun rejectsMalformedIpv4() {
            listOf("1.2.3", "256.0.0.1", "010.0.0.1", "0x7f.0.0.1", "1.2.3.4.5", "1.2.3.4%eth0", "[1.2.3.4]", "").forEach {
                assertNull(IpAddress.parse(it), it)
            }
        }

        @Test
        @DisplayName("Rejects malformed IPv6")
        fun rejectsMalformedIpv6() {
            listOf(":::", "1::2::3", "12345::", "1.2.3.4:80", "[::1]:443", "[::1", "::1%").forEach {
                assertNull(IpAddress.parse(it), it)
            }
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

        @Test
        @DisplayName("Treats a bare address as a single host")
        fun bareAddressIsSingleHost() {
            val ipv4 = range("203.0.113.5")
            assertTrue(address("203.0.113.5") in ipv4)
            assertFalse(address("203.0.113.6") in ipv4)
        }

        @Test
        @DisplayName("Does not match across address families")
        fun doesNotMatchAcrossFamilies() {
            assertFalse(address("::1") in range("0.0.0.0/0"))
            assertFalse(address("127.0.0.1") in range("::/0"))
        }

        @Test
        @DisplayName("Reads an IPv4-mapped range as IPv4")
        fun readsMappedRange() {
            assertTrue(address("10.1.2.3") in range("::ffff:10.0.0.0/104"))
            assertFalse(address("11.1.2.3") in range("::ffff:10.0.0.0/104"))
            assertNull(CidrRange.parse("::ffff:10.0.0.0/90"))
        }

        @Test
        @DisplayName("Rejects malformed ranges")
        fun rejectsMalformedRanges() {
            listOf("10.0.0.0/33", "::1/129", "10.0.0.0/", "10.0.0.0/+8", "10.0.0.0/-1", "10.0.0.0/8/8", "/8", "proxy.example.com").forEach {
                assertNull(CidrRange.parse(it), it)
            }
        }
    }
}
