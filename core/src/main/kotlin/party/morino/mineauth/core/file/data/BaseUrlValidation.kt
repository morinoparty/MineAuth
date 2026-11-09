package party.morino.mineauth.core.file.data

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import java.net.URI
import java.net.URISyntaxException

// 127.0.0.0/8 のIPv4ループバックアドレス（"127.0.0.1.example.com" などは一致させない）
private val IPV4_LOOPBACK = Regex("""127(\.\d{1,3}){3}""")

// java.net.URIはIPv6アドレスのホストを角括弧付きで返す
private val IPV6_LOOPBACK = setOf("[::1]", "[0:0:0:0:0:0:0:1]")

/**
 * server.baseUrlを検証する
 *
 * baseUrlはOIDCのissuerと、Discoveryで公開する全エンドポイントの接頭辞になる。
 * issuerはクエリとフラグメントを持てない（OIDC Discovery 1.0 Section 3 / RFC 8414 Section 2）。
 * エンドポイントは文字列連結で作るためURLの途中にも混入するので、読み込み時に拒否する。
 *
 * @return 検証済みのURI。不正な場合はその理由
 */
fun ServerConfig.validateBaseUrl(): Either<ConfigError, URI> = either {
    // java.net.URIの解析結果を判定の土台にする
    val uri = Either.catchOrThrow<URISyntaxException, URI> { URI(baseUrl) }
        .mapLeft { ConfigError.BaseUrlUnparsable(baseUrl, it.message.orEmpty()) }
        .bind()

    // スキームを省略した "api.example.com" は全体がパスとして解釈されるため、ホストなしになる
    ensureNotNull(hostOf(uri)) { ConfigError.BaseUrlMissingHost(baseUrl) }
    // "//api.example.com" はホストを持つがスキームがなく、issuerとして使えない
    ensureNotNull(uri.scheme) { ConfigError.BaseUrlUnparsable(baseUrl, "the scheme is missing") }

    // 空のクエリ・フラグメント（末尾の "?" や "#"）も構成要素があるものとして拒否する
    ensure(uri.rawQuery == null) { ConfigError.BaseUrlHasQuery(baseUrl) }
    ensure(uri.rawFragment == null) { ConfigError.BaseUrlHasFragment(baseUrl) }

    uri
}

/**
 * baseUrlについてhttps未使用の警告を出すべきかを判定する
 *
 * issuerとjwks_uriはhttpsでなければならないが、開発環境ではループバックアドレスのhttpを使うため警告しない。
 *
 * @param uri validateBaseUrlで検証済みのbaseUrl
 * @return httpsではなく、かつループバックアドレスでもない場合はtrue
 */
fun isInsecureBaseUrl(uri: URI): Boolean {
    val isLoopback = hostOf(uri)?.let(::isLoopbackHost) ?: false
    return !uri.scheme.equals("https", ignoreCase = true) && !isLoopback
}

/**
 * URIからホスト名を取り出す
 *
 * java.net.URIはアンダースコアを含むホスト名（Dockerのサービス名など）をレジストリベースとして扱いhostをnullにする。
 * 実在するホスト名を不正と誤判定しないよう、その場合はauthorityからuserinfoとポートを除いて判定する。
 *
 * @param uri 対象のURI
 * @return ホスト名。ない場合はnull
 */
private fun hostOf(uri: URI): String? =
    uri.host ?: uri.rawAuthority
        ?.substringAfterLast('@')
        ?.substringBeforeLast(':')
        ?.takeIf { it.isNotBlank() }

/**
 * ホスト名がループバックアドレスかどうかを判定する
 *
 * 判定結果がDNSに左右されないよう、また起動を遅らせないよう、名前解決はせず表記だけで判定する。
 *
 * @param host 判定するホスト名
 * @return ループバックアドレスならtrue
 */
private fun isLoopbackHost(host: String): Boolean {
    // ホスト名は大文字小文字を区別せず、末尾のドットは同じ名前を指す
    val normalized = host.lowercase().removeSuffix(".")
    // RFC 6761により *.localhost もループバックに解決される
    return normalized == "localhost" ||
        normalized.endsWith(".localhost") ||
        IPV4_LOOPBACK.matches(normalized) ||
        normalized in IPV6_LOOPBACK
}
