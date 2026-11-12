package party.morino.mineauth.core.web.ratelimit

/**
 * 接続元とX-Forwarded-Forから、流量制限で数えるクライアントを決定する
 * リクエストの内容だけで決まる純粋な処理で、名前解決も行わない
 */
object ClientIpResolver {

    /**
     * クライアントのキーを解決する
     *
     * 信頼できない接続元のヘッダーは誰でも偽装できるので読まない。
     * 信頼できるプロキシは右端に接続元を追記していくので、右から順に信頼できるプロキシを読み飛ばし、
     * 最初に現れた信頼できないアドレスをクライアントとする。左側はクライアントが自由に書けるので優先しない
     *
     * @param peer TCP接続の相手（call.request.local.remoteAddress）
     * @param forwardedFor X-Forwarded-Forヘッダーの値。無い場合はnull
     * @param trustedProxies 信頼するプロキシの範囲
     * @return クライアントのキー。特定できない場合は接続元のキー
     */
    fun resolve(peer: String, forwardedFor: String?, trustedProxies: List<CidrRange>): ClientIpKey {
        // テスト環境の "localhost" のようなIPリテラルでない接続元は、信頼せず文字列のままキーにする
        val peerAddress = IpAddress.parse(peer) ?: return ClientIpKey(peer)
        val peerKey = peerAddress.toKey()
        if (forwardedFor == null || !peerAddress.isTrustedBy(trustedProxies)) return peerKey

        for (entry in forwardedFor.split(',').asReversed()) {
            // 解釈できない値より左は信頼の連鎖が切れているので、それ以上たどらない
            val address = IpAddress.parse(entry.trim()) ?: return peerKey
            if (!address.isTrustedBy(trustedProxies)) return address.toKey()
        }
        // 全ての値が信頼できるプロキシの場合は、接続元より手前のクライアントを特定できない
        return peerKey
    }

    /**
     * 接続元がX-Forwarded-Forを読んでよい相手かどうかを判定する
     *
     * @param peer TCP接続の相手
     * @param trustedProxies 信頼するプロキシの範囲
     * @return 信頼できるプロキシの場合はtrue。IPリテラルでない場合はfalse
     */
    fun isTrustedPeer(peer: String, trustedProxies: List<CidrRange>): Boolean =
        IpAddress.parse(peer)?.isTrustedBy(trustedProxies) ?: false

    private fun IpAddress.isTrustedBy(trustedProxies: List<CidrRange>): Boolean = trustedProxies.any { this in it }
}
