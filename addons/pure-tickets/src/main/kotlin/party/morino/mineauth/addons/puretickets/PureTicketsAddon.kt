package party.morino.mineauth.addons.puretickets

import broccolai.tickets.api.service.storage.StorageService
import broccolai.tickets.api.service.ticket.TicketService
import broccolai.tickets.api.service.user.UserService
import com.google.inject.Injector
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.puretickets.routes.TicketHandler
import party.morino.mineauth.api.EndpointRegistrationException
import party.morino.mineauth.api.MineAuthApi

/**
 * PureTickets連携アドオン
 * MineAuthのHTTP API経由でPureTicketsのチケットデータにアクセス可能にする
 */
class PureTicketsAddon : JavaPlugin() {

    private lateinit var mineAuthApi: MineAuthApi

    override fun onEnable() {
        logger.info("PureTickets Addon enabling...")

        // MineAuthApiの取得（ServicesManager経由）
        val api = MineAuthApi.get(server)
        if (api == null) {
            logger.severe("MineAuth plugin not found")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthApi = api

        // PureTicketsの内部サービスを取得してKoinに登録
        if (!setupKoin()) {
            logger.severe("Failed to setup Koin - PureTickets services not accessible")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        setupMineAuth()

        logger.info("PureTickets Addon enabled")
    }

    override fun onDisable() {
        stopKoin()
        logger.info("PureTickets Addon disabled")
    }

    /**
     * Koinの初期化
     * PureTicketsの内部サービスをリフレクション経由で取得しDIコンテナに登録する
     *
     * PureTicketsはAPIを外部公開していないため、リフレクションで
     * PaperPlatform.pureTickets → PureTickets.injector の順にアクセスし
     * 取得したGuice Injectorから各サービスを取得する
     *
     * @return 初期化に成功した場合はtrue
     */
    private fun setupKoin(): Boolean {
        val pureTicketsPlugin = server.pluginManager.getPlugin("Tickets")
        if (pureTicketsPlugin == null) {
            logger.warning("PureTickets plugin not found")
            return false
        }

        // リフレクションでGuice Injectorを取得
        val injector = extractInjector(pureTicketsPlugin) ?: return false

        // Injectorから各サービスを取得
        val ticketService = getServiceFromInjector<TicketService>(injector, TicketService::class.java) ?: return false
        val storageService = getServiceFromInjector<StorageService>(injector, StorageService::class.java) ?: return false
        val userService = getServiceFromInjector<UserService>(injector, UserService::class.java) ?: return false

        logger.info("PureTickets services acquired successfully")

        startKoin {
            modules(
                module {
                    // PureTicketsのサービスをシングルトンとして登録
                    single<TicketService> { ticketService }
                    single<StorageService> { storageService }
                    single<UserService> { userService }
                }
            )
        }
        return true
    }

    /**
     * Guice Injectorからサービスインスタンスを取得する
     *
     * Guice は PureTickets が実行時にロードするものを compileOnly で参照しているため、
     * 型付きで Injector.getInstance を直接呼び出せる
     *
     * @param injector Guice Injector
     * @param serviceClass 取得するサービスのクラス
     * @return サービスインスタンス、取得失敗時（バインディング未定義など）はnull
     */
    private fun <T> getServiceFromInjector(injector: Injector, serviceClass: Class<T>): T? {
        return runCatching {
            injector.getInstance(serviceClass)
        }.getOrElse { e ->
            logger.severe("Failed to get ${serviceClass.simpleName} from PureTickets: ${e.message}")
            null
        }
    }

    /**
     * PureTicketsプラグインからGuice Injectorをリフレクションで取得する
     *
     * アクセスパス: PaperPlatform → pureTickets(PureTickets) → injector(Guice Injector)
     *
     * @param plugin PureTicketsのプラグインインスタンス
     * @return Guice Injector、取得失敗時はnull
     */
    private fun extractInjector(plugin: org.bukkit.plugin.Plugin): Injector? {
        return runCatching {
            // PaperPlatformからpureTicketsフィールドを取得
            val pureTicketsField = plugin.javaClass.getDeclaredField("pureTickets")
            pureTicketsField.isAccessible = true
            val pureTickets = pureTicketsField.get(plugin)
                ?: throw IllegalStateException("pureTickets field is null (plugin not fully initialized?)")

            // PureTicketsからinjectorフィールドを取得
            val injectorField = pureTickets.javaClass.getDeclaredField("injector")
            injectorField.isAccessible = true
            injectorField.get(pureTickets) as? Injector
                ?: throw IllegalStateException("injector field is null or not a Guice Injector")
        }.getOrElse { e ->
            logger.severe("Failed to extract Guice Injector from PureTickets via reflection: ${e.message}")
            logger.severe("This addon requires PureTickets v5.x. Check compatibility if you see this error.")
            null
        }
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/tickets/ 配下で利用可能
     */
    private fun setupMineAuth() {
        try {
            val registration = mineAuthApi.register(this, "tickets", TicketHandler())
            logger.info("Mounted ${registration.endpoints.size} endpoints under ${registration.basePath}")
        } catch (e: EndpointRegistrationException) {
            logger.severe(e.message)
            server.pluginManager.disablePlugin(this)
        }
    }
}
