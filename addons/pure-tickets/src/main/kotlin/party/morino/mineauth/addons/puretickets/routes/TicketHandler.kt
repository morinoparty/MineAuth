package party.morino.mineauth.addons.puretickets.routes

import broccolai.tickets.api.model.interaction.MessageInteraction
import broccolai.tickets.api.model.ticket.Ticket
import broccolai.tickets.api.model.ticket.TicketStatus
import broccolai.tickets.api.service.storage.StorageService
import broccolai.tickets.api.service.ticket.TicketService
import broccolai.tickets.api.service.user.UserService
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.puretickets.data.InteractionResponse
import party.morino.mineauth.addons.puretickets.data.PaginatedTicketsResponse
import party.morino.mineauth.addons.puretickets.data.TicketDetailResponse
import party.morino.mineauth.addons.puretickets.data.TicketResponse
import party.morino.mineauth.addons.puretickets.data.TicketsResponse
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.annotations.Authenticated
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.Path
import party.morino.mineauth.api.annotations.PlayerParam
import party.morino.mineauth.api.annotations.Query
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import java.time.format.DateTimeFormatter
import java.util.EnumSet
import java.util.UUID

/**
 * PureTicketsのチケットデータを提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class TicketHandler : KoinComponent {
    private val ticketService: TicketService by inject()
    private val storageService: StorageService by inject()
    private val userService: UserService by inject()

    companion object {
        /** 全件一覧の1ページあたりのデフォルト取得件数 */
        const val DEFAULT_LIMIT = 50

        /** 全件一覧の1ページあたりの最大取得件数（これを超える指定はクランプされる） */
        const val MAX_LIMIT = 200

        /** PureTicketsがスタッフ向け「全チケット閲覧」に割り当てているパーミッションノード */
        const val STAFF_LIST_PERMISSION = "tickets.staff.list"
    }

    /**
     * サーバー全体のチケット一覧をカーソルベースページネーションで取得する
     * GET /tickets?status={status}&player={player}&cursor={ticketId}&limit={limit}
     *
     * 運営側のトリアージ用途を想定しており、オフラインプレイヤーの分も含めて返す。
     * ユーザートークンの場合はPureTicketsのスタッフ権限（tickets.staff.list）が必要で、
     * サービストークンは権限チェックの対象外となる。
     *
     * @param status 絞り込むステータス（カンマ区切りで複数指定可。省略時は全ステータス）
     * @param player 絞り込むプレイヤー（UUIDまたはプレイヤー名。省略時は全プレイヤー）
     * @param cursor カーソル（このチケットIDより後のチケットを返す。省略時は先頭から）
     * @param limit 取得件数（省略時はデフォルト値、最大値でクランプされる）
     * @return ページネーション付きチケット一覧
     */
    @Get("/tickets")
    @Authenticated(permission = STAFF_LIST_PERMISSION, callers = [CallerType.USER, CallerType.SERVICE])
    suspend fun listAllTickets(
        @Query("status") status: String?,
        @Query("player") player: String?,
        @Query("cursor") cursor: Int?,
        @Query("limit") limit: Int?,
    ): PaginatedTicketsResponse {
        // クエリのバリデーション（不正な値は400で早期に弾く）
        val statuses = parseStatuses(status)
        val resolvedLimit = resolveLimit(limit)
        val playerUuid = player?.let { resolvePlayerUuid(it) }

        // プレイヤー指定がある場合はそのプレイヤーの分だけ取得し、無駄な全件走査を避ける
        val tickets: Collection<Ticket> = if (playerUuid != null) {
            ticketService.get(userService.wrap(playerUuid), statuses)
        } else {
            ticketService.get(statuses).values.flatten()
        }

        // フィルタ条件に一致した全件をIDの昇順で固定する（totalはカーソルに関係なくこの件数）
        // PureTickets側のキャッシュ状態に依存しないよう、ステータスはこちらでも念のため絞り込む
        val matched = tickets.filter { it.status() in statuses }.sortedBy { it.id() }

        // 排他的カーソルでページングし、1件多く取得してhasMoreを判定する
        val fetched = matched.asSequence()
            .let { seq -> if (cursor != null) seq.filter { it.id() > cursor } else seq }
            .take(resolvedLimit + 1)
            .toList()
        val hasMore = fetched.size > resolvedLimit
        val page = if (hasMore) fetched.dropLast(1) else fetched

        // 次のカーソルは結果の最後のチケットID
        val nextCursor = if (hasMore) page.lastOrNull()?.id()?.toString() else null

        return PaginatedTicketsResponse(
            tickets = page.map { it.toResponse() },
            total = matched.size,
            nextCursor = nextCursor,
            hasMore = hasMore,
        )
    }

    /**
     * プレイヤーのチケット一覧を取得する
     * GET /tickets/{player}
     *
     * 全ステータス（OPEN / CLAIMED / CLOSED）のチケットを返す
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @return チケット一覧のレスポンス
     */
    @Get("/tickets/{player}")
    @Authenticated(callers = [CallerType.USER, CallerType.SERVICE])
    suspend fun getTickets(@PlayerParam("player") player: OfflinePlayer): TicketsResponse {
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
    @Get("/tickets/{player}/{id}")
    @Authenticated(callers = [CallerType.USER, CallerType.SERVICE])
    suspend fun getTicketDetail(
        @PlayerParam("player") player: OfflinePlayer,
        @Path("id") id: Int,
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
     * statusクエリをTicketStatusの集合に変換する
     *
     * カンマ区切り・大文字小文字を区別しない（例: "open,claimed"）。
     * 省略時は全ステータスを対象にする。
     *
     * @param status クエリで受け取った文字列（null可）
     * @return 絞り込み対象のステータス集合
     * @throws HttpError 未知のステータス名が含まれる場合（400）
     */
    private fun parseStatuses(status: String?): Set<TicketStatus> {
        if (status.isNullOrBlank()) return EnumSet.allOf(TicketStatus::class.java)

        val parsed = status.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { name ->
                runCatching { TicketStatus.valueOf(name.uppercase()) }.getOrElse {
                    val allowed = TicketStatus.entries.joinToString(", ") { it.name }
                    throw HttpError(HttpStatus.BAD_REQUEST, "Unknown ticket status: $name (allowed: $allowed)")
                }
            }

        // "," のみなど、有効な値が1つもない場合は全ステータスとみなす
        return if (parsed.isEmpty()) EnumSet.allOf(TicketStatus::class.java) else EnumSet.copyOf(parsed)
    }

    /**
     * playerクエリをUUIDに解決する
     *
     * UUID文字列はそのまま使用し、それ以外はプレイヤー名としてサーバーのローカルキャッシュから解決する。
     * Bukkit.getOfflinePlayer(name)はMojang APIへのブロッキングI/Oを伴うため使用しない。
     *
     * @param player クエリで受け取った文字列（UUIDまたはプレイヤー名）
     * @return 解決されたUUID
     * @throws HttpError プレイヤー名がキャッシュに存在しない場合（404）
     */
    private fun resolvePlayerUuid(player: String): UUID {
        runCatching { UUID.fromString(player) }.getOrNull()?.let { return it }

        return Bukkit.getOfflinePlayerIfCached(player)?.uniqueId
            ?: throw HttpError(HttpStatus.NOT_FOUND, "Player not found: $player (specify a UUID for players who have never joined)")
    }

    /**
     * limitクエリを検証し、実際に使用する件数に解決する
     *
     * @param limit クエリで受け取った件数（null可）
     * @return 使用する件数（省略時はデフォルト値、最大値でクランプ）
     * @throws HttpError 0以下が指定された場合（400）
     */
    private fun resolveLimit(limit: Int?): Int {
        if (limit == null) return DEFAULT_LIMIT
        if (limit <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Limit must be greater than 0")
        }
        return limit.coerceAtMost(MAX_LIMIT)
    }

    /**
     * TicketをTicketResponseに変換するヘルパー
     */
    private fun Ticket.toResponse(): TicketResponse {
        return TicketResponse(
            id = id(),
            player = player().toString(),
            status = status().name,
            message = message().message(),
            claimer = claimer().orElse(null)?.toString(),
        )
    }
}
