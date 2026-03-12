package party.morino.mineauth.addons.puretickets

import broccolai.tickets.api.service.storage.StorageService
import broccolai.tickets.api.service.ticket.TicketService
import broccolai.tickets.api.service.user.UserService
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.puretickets.routes.TicketHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * PureTickets連携アドオン
 * MineAuthのHTTP API経由でPureTicketsのチケットデータにアクセス可能にする
 */
class PureTicketsAddon : JavaPlugin() {

    private lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("PureTickets Addon enabling...")

        // MineAuthAPIの取得
        val mineAuthPlugin = server.pluginManager.getPlugin("MineAuth")
        val api = mineAuthPlugin as? MineAuthAPI
        if (api == null) {
            logger.severe("MineAuth plugin not found or is not a valid MineAuthAPI instance")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthAPI = api

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
     * Guice InjectorのgetInstanceメソッドをリフレクションで呼び出して各サービスを取得する
     *
     * @return 初期化に成功した場合はtrue
     */
    private fun setupKoin(): Boolean {
        val pureTicketsPlugin = server.pluginManager.getPlugin("PureTickets")
        if (pureTicketsPlugin == null) {
            logger.warning("PureTickets plugin not found")
            return false
        }

        // リフレクションでGuice Injectorオブジェクトを取得（型はAny）
        val injector = extractInjector(pureTicketsPlugin) ?: return false

        // Guice Injector.getInstance(Class)をリフレクションで呼び出す
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
     * Guice Injectorからサービスインスタンスをリフレクションで取得する
     *
     * Guiceクラスがclasspathに存在しないため、getInstance(Class)をリフレクションで呼び出す
     *
     * @param injector Guice Injectorオブジェクト
     * @param serviceClass 取得するサービスのクラス
     * @return サービスインスタンス、取得失敗時はnull
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getServiceFromInjector(injector: Any, serviceClass: Class<T>): T? {
        return runCatching {
            // Injector.getInstance(Class<T>)をリフレクションで呼び出し
            val getInstanceMethod = injector.javaClass.getMethod("getInstance", Class::class.java)
            getInstanceMethod.invoke(injector, serviceClass) as T
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
     * @return Guice Injectorオブジェクト（型はAny）、取得失敗時はnull
     */
    private fun extractInjector(plugin: org.bukkit.plugin.Plugin): Any? {
        return runCatching {
            // PaperPlatformからpureTicketsフィールドを取得
            val pureTicketsField = plugin.javaClass.getDeclaredField("pureTickets")
            pureTicketsField.isAccessible = true
            val pureTickets = pureTicketsField.get(plugin)
                ?: throw IllegalStateException("pureTickets field is null (plugin not fully initialized?)")

            // PureTicketsからinjectorフィールドを取得
            val injectorField = pureTickets.javaClass.getDeclaredField("injector")
            injectorField.isAccessible = true
            injectorField.get(pureTickets)
                ?: throw IllegalStateException("injector field is null")
        }.getOrElse { e ->
            logger.severe("Failed to extract Guice Injector from PureTickets via reflection: ${e.message}")
            logger.severe("This addon requires PureTickets v5.x. Check compatibility if you see this error.")
            null
        }
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/pure-tickets-addon/ 配下で利用可能
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(TicketHandler())
    }
}
