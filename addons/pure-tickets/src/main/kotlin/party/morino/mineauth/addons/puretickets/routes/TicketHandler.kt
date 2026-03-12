package party.morino.mineauth.addons.puretickets.routes

import broccolai.tickets.api.model.interaction.MessageInteraction
import broccolai.tickets.api.model.ticket.Ticket
import broccolai.tickets.api.model.ticket.TicketStatus
import broccolai.tickets.api.service.storage.StorageService
import broccolai.tickets.api.service.ticket.TicketService
import broccolai.tickets.api.service.user.UserService
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.puretickets.data.InteractionResponse
import party.morino.mineauth.addons.puretickets.data.TicketDetailResponse
import party.morino.mineauth.addons.puretickets.data.TicketResponse
import party.morino.mineauth.addons.puretickets.data.TicketsResponse
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.annotations.PathParam
import party.morino.mineauth.api.annotations.TargetPlayer
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import java.time.format.DateTimeFormatter
import java.util.EnumSet

/**
 * PureTicketsのチケットデータを提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class TicketHandler : KoinComponent {
    private val ticketService: TicketService by inject()
    private val storageService: StorageService by inject()
    private val userService: UserService by inject()

    /**
     * プレイヤーのチケット一覧を取得する
     * GET /tickets/{player}
     *
     * 全ステータス（OPEN / CLAIMED / CLOSED）のチケットを返す
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @return チケット一覧のレスポンス
     */
    @GetMapping("/tickets/{player}")
    suspend fun getTickets(@TargetPlayer player: OfflinePlayer): TicketsResponse {
        // UUIDからSoulを生成してチケットを検索
        val soul = userService.wrap(player.uniqueId)
        val allStatuses = EnumSet.allOf(TicketStatus::class.java)
        val tickets = ticketService.get(soul, allStatuses)

        val ticketResponses = tickets.map { ticket -> ticket.toResponse() }

        return TicketsResponse(
            tickets = ticketResponses,
            total = ticketResponses.size,
        )
    }

    /**
     * チケットの詳細を取得する（操作履歴を含む）
     * GET /tickets/{player}/{id}
     *
     * 指定されたプレイヤーが所有するチケットのみ返す（他人のチケットは403）
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @param id チケットID
     * @return チケット詳細のレスポンス
     */
    @GetMapping("/tickets/{player}/{id}")
    suspend fun getTicketDetail(
        @TargetPlayer player: OfflinePlayer,
        @PathParam("id") id: Int,
    ): TicketDetailResponse {
        // チケットをIDで取得
        val ticket = ticketService.get(id).orElse(null)
            ?: throw HttpError(HttpStatus.NOT_FOUND, "Ticket not found: $id")

        // チケットの所有者を検証（他人のチケットへのアクセスを防止）
        if (ticket.player() != player.uniqueId) {
            throw HttpError(HttpStatus.FORBIDDEN, "You do not own this ticket")
        }

        // チケットに紐づくインタラクション履歴を取得
        val interactions = storageService.interactions(ticket)

        val interactionResponses = interactions.map { interaction ->
            InteractionResponse(
                action = interaction.action().name,
                time = interaction.time().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                sender = interaction.sender().toString(),
                // MessageInteractionの場合のみメッセージを含める
                message = (interaction as? MessageInteraction)?.message(),
            )
        }

        return TicketDetailResponse(
            id = ticket.id(),
            status = ticket.status().name,
            message = ticket.message().message(),
            claimer = ticket.claimer().orElse(null)?.toString(),
            interactions = interactionResponses,
        )
    }

    /**
     * TicketをTicketResponseに変換するヘルパー
     */
    private fun Ticket.toResponse(): TicketResponse {
        return TicketResponse(
            id = id(),
            status = status().name,
            message = message().message(),
            claimer = claimer().orElse(null)?.toString(),
        )
    }
}
