package party.morino.mineauth.core.web.components.plugin

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.AccessInfo
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.HttpMethod
import party.morino.mineauth.api.RegisteredEndpoint
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RegisteredEndpointDataの変換ロジックのユニットテスト
 * 公開/認証必須エンドポイントのアクセス情報が正しくレスポンス形式に写像されることを検証する
 */
class RegisteredEndpointDataTest {

    private fun endpoint(access: AccessInfo) = RegisteredEndpoint(
        httpMethod = HttpMethod.GET,
        fullPath = "/api/v1/plugins/tickets/tickets",
        handlerClass = "party.morino.mineauth.addons.puretickets.routes.TicketHandler",
        functionName = "listAllTickets",
        access = access,
    )

    @Test
    @DisplayName("Public endpoint omits permission and callers")
    fun publicEndpoint() {
        val data = RegisteredEndpointData.from(endpoint(AccessInfo.Public))

        assertEquals("GET", data.method)
        assertEquals("/api/v1/plugins/tickets/tickets", data.path)
        assertEquals(RegisteredEndpointData.ACCESS_PUBLIC, data.access)
        assertNull(data.permission)
        assertNull(data.callers)
    }

    @Test
    @DisplayName("Authenticated endpoint exposes permission and callers in enum order")
    fun authenticatedEndpoint() {
        // callersの元がSetでも、出力順はUSER, SERVICEの宣言順に固定される
        val access = AccessInfo.Authenticated(
            permission = "tickets.staff.list",
            callers = setOf(CallerType.SERVICE, CallerType.USER),
        )

        val data = RegisteredEndpointData.from(endpoint(access))

        assertEquals(RegisteredEndpointData.ACCESS_AUTHENTICATED, data.access)
        assertEquals("tickets.staff.list", data.permission)
        assertEquals(listOf("USER", "SERVICE"), data.callers)
    }

    @Test
    @DisplayName("Authenticated endpoint without permission keeps permission null")
    fun authenticatedWithoutPermission() {
        val data = RegisteredEndpointData.from(
            endpoint(AccessInfo.Authenticated(permission = null, callers = setOf(CallerType.USER)))
        )

        assertNull(data.permission)
        assertEquals(listOf("USER"), data.callers)
    }
}
