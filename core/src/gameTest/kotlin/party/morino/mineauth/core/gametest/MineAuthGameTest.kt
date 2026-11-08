package party.morino.mineauth.core.gametest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.server.GameServer

/**
 * 本物の Paper サーバーでしか確かめられない MineAuth の振る舞いのゲームテスト
 *
 * 単体テストでは、plugin.yml の libraries を Paper が取得して MineAuth を有効化できるか、
 * relocate した Ktor と Jetty が実サーバー上で応答するか、コンソールからの reload で HTTP サーバーが戻るかを確かめられない。
 * ここではその点だけを見る。サーバーの起動は重いので、新しいテストは単体テストで確かめられない場合にだけ足す。
 */
@ExtendWith(MineAuthServer::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MineAuthGameTest {
    @Test
    @Order(1)
    @DisplayName("Serves OIDC discovery and JWKS after enabling")
    suspend fun `serves discovery and jwks after enabling`(arena: MineAuthServer) {
        // fukurou は MineAuth の有効化を確認してからテストを始めるので、ここでは HTTP サーバーの応答を見る
        assertEndpointsAnswer(arena)
    }

    @Test
    @Order(2)
    @DisplayName("Keeps endpoints answering after console reload")
    suspend fun `keeps endpoints answering after console reload`(arena: MineAuthServer, server: GameServer) {
        // reload の前に公開していた鍵（このテストだけを実行した場合は起動を待つ）
        val keyIdsBefore = keyIdsOf(arena.http.awaitJson(JWKS_PATH))

        // RCON のコンソールから実行する。ReloadCommand は設定とHTTPサーバーの両方を戻せたときだけ Reloaded を返す
        val response = server.command("mineauth reload").requireText()
        assertTrue("Reloaded" in response, "unexpected reload response: $response")

        // HTTP サーバーが作り直された後も応答し、発行済みトークンを検証できるよう同じ鍵を公開し続ける
        val keyIdsAfter = assertEndpointsAnswer(arena)
        assertEquals(keyIdsBefore, keyIdsAfter)
    }

    /**
     * OIDC Discovery の issuer が baseUrl と一致し、JWK Set に鍵があることを確かめる
     *
     * @param arena MineAuth のサーバー
     * @return JWK Set に含まれる鍵の kid
     */
    private suspend fun assertEndpointsAnswer(arena: MineAuthServer): List<String> {
        val discovery = arena.http.awaitJson(DISCOVERY_PATH)
        // issuer はトークンの iss と同じく server.baseUrl から作られる
        assertEquals(arena.baseUrl, discovery.getValue("issuer").jsonPrimitive.content)

        val keyIds = keyIdsOf(arena.http.awaitJson(JWKS_PATH))
        assertTrue(keyIds.isNotEmpty(), "the JWK Set has no key")
        return keyIds
    }

    /**
     * JWK Set から鍵の kid を取り出す
     *
     * @param jwks JWK Set（RFC 7517）
     * @return 各鍵の kid
     */
    private fun keyIdsOf(jwks: JsonObject): List<String> =
        jwks.getValue("keys").jsonArray.map { key -> key.jsonObject.getValue("kid").jsonPrimitive.content }

    private companion object {
        // OIDC Discovery 1.0 のエンドポイント
        const val DISCOVERY_PATH = "/.well-known/openid-configuration"

        // jwks_uri のエンドポイント
        const val JWKS_PATH = "/.well-known/jwks.json"
    }
}
