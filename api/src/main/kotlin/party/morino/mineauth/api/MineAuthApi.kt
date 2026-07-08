package party.morino.mineauth.api

import org.bukkit.Server
import org.bukkit.plugin.java.JavaPlugin

/**
 * MineAuthプラグインのAPIインターフェース
 * 他のプラグインがHTTPエンドポイントを登録するためのエントリーポイント
 *
 * インスタンスはBukkitのServicesManager経由で取得する:
 * ```kotlin
 * val api = MineAuthApi.get(server)
 *     ?: error("MineAuth is not available")
 * val registration = api.register(this, ShopHandler())
 * logger.info("Mounted at ${registration.basePath}")
 * ```
 */
interface MineAuthApi {

    /**
     * ハンドラーのエンドポイントを登録する
     *
     * 登録はall-or-nothing: いずれかのエンドポイントが検証に失敗した場合、
     * 何もマウントされず[EndpointRegistrationException]がスローされる。
     * Webサーバーの起動前・起動後どちらでも呼び出し可能。
     *
     * ベースパスは `/api/v1/plugins/{plugin.name.lowercase()}` となる。
     *
     * @param plugin 登録元プラグイン（プラグイン無効化時に自動で登録解除される）
     * @param handlers `@Get`/`@Post`等のアノテーションが付与されたメソッドを持つハンドラーインスタンス
     * @return 登録結果（実際のマウントパスとエンドポイント一覧を含む）
     * @throws EndpointRegistrationException 検証エラーの全リストを含む
     */
    fun register(plugin: JavaPlugin, vararg handlers: Any): MineAuthRegistration

    /**
     * URL名前空間を明示してハンドラーのエンドポイントを登録する
     *
     * プラグイン名がURLとして不格好な場合（例: "mineauth-addon-vault"）に、
     * 短い名前空間（例: "vault"）を指定できる。
     * 名前空間は `[a-z0-9-]{2,32}` に一致する必要があり、
     * 他プラグインが使用中の名前空間は登録できない。
     *
     * @param plugin 登録元プラグイン
     * @param namespace URL名前空間（ベースパスは `/api/v1/plugins/{namespace}`）
     * @param handlers ハンドラーインスタンス
     * @return 登録結果
     * @throws EndpointRegistrationException 検証エラーの全リストを含む
     */
    fun register(plugin: JavaPlugin, namespace: String, vararg handlers: Any): MineAuthRegistration

    companion object {
        /**
         * ServicesManagerからMineAuthApiのインスタンスを取得する
         *
         * @param server Bukkitサーバーインスタンス
         * @return MineAuthApi、MineAuthが未ロードの場合はnull
         */
        @JvmStatic
        fun get(server: Server): MineAuthApi? =
            server.servicesManager.getRegistration(MineAuthApi::class.java)?.provider
    }
}
