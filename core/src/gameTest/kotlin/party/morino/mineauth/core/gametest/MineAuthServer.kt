package party.morino.mineauth.core.gametest

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.plugin.PluginSource
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.server.ServerSpec
import party.morino.fukurou.server.ServerType
import party.morino.fukurou.server.paper.Paper
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/**
 * MineAuth を入れた Paper サーバー（fukurou の拡張 1 つ = サーバー 1 台）
 *
 * テスト対象の shadowJar（-Dfukurou.plugin.mineauth）と、plugin.yml でハード依存にしている LuckPerms を入れて起動する。
 * HTTP とコンソールだけで確かめられるので、プレイヤー（クライアント）は参加させない。
 */
class MineAuthServer : GameServerExtension() {
    /** MineAuth の server.baseUrl。OIDC Discovery の issuer はこの値になる */
    val baseUrl: String
        get() = baseUrlOf(httpPort)

    /** MineAuth の HTTP サーバーへのクライアント */
    val http: MineAuthHttpClient by lazy { MineAuthHttpClient(baseUrl) }

    /**
     * サーバーの種類
     * CI は -Pfukurou.minecraftVersion を渡し、手元では paper-api と同じバージョンを使う
     */
    override fun type(config: FukurouConfig): ServerType =
        Paper.fromProperties(config, defaultVersion = DEFAULT_MINECRAFT_VERSION)

    /**
     * サーバーの宣言
     * テスト対象の MineAuth、依存の LuckPerms、空きポートを指定した config.json を用意する
     */
    override fun ServerSpec.configure() {
        // result id を paper-<version>-mineauth にする
        label = "mineauth"
        plugins {
            // Gradle の gameTest タスクが shadowJar のパスを渡す
            underTest(PluginSource.systemProperty("mineauth"))
            // ハード依存（depend: [LuckPerms]）なので、無いと MineAuth が有効化されない
            dependency(PluginSource.url(LUCKPERMS_URL, LUCKPERMS_SHA256))
        }
        // サーバーディレクトリへ最初にコピーされるので、MineAuth は初回起動からこの config.json を読む
        serverFiles(serverFilesDirectory)
        // プレイヤーがいないのでワールドのリセットは要らない
        isolation = Isolation.None
    }

    /**
     * JVM で共有する値
     * JUnit はテストクラスごとに拡張のインスタンスを作りうるが、config.json を書くのは最初の 1 回だけなので、
     * インスタンスごとにポートを選ぶと baseUrl と config.json が食い違う。そのためポートとファイルは JVM で 1 つにする
     */
    private companion object {
        // gradle/libs.versions.toml の paper（paper-api）と同じ Minecraft のバージョン
        const val DEFAULT_MINECRAFT_VERSION = "26.3"

        // Minecraft 26.3 に対応する LuckPerms（Modrinth の v5.5.71-bukkit）
        const val LUCKPERMS_URL = "https://cdn.modrinth.com/data/Vebnzrzj/versions/b0mk8uS6/LuckPerms-Bukkit-5.5.71.jar"

        // Modrinth は sha1 と sha512 しか公開しないので、それらと一致したファイルから計算した sha256
        const val LUCKPERMS_SHA256 = "49cecb66fa1fd22a133039a490e9c1e5095a238e7cd66eb9d2a16fe6c897550d"

        /** MineAuth の HTTP サーバーのポート */
        val httpPort: Int by lazy { findFreePort() }

        /** plugins/MineAuth/config.json を置いたディレクトリ（fukurou の serverFiles） */
        val serverFilesDirectory: Path by lazy { writeServerFiles(httpPort) }

        /**
         * ポートから baseUrl を作る
         *
         * @param port HTTP サーバーのポート
         * @return ループバックアドレスの baseUrl（http でも警告されない）
         */
        fun baseUrlOf(port: Int): String = "http://127.0.0.1:$port"

        /**
         * 空いているポートを OS に選ばせる
         *
         * @return 割り当てられたポート番号
         */
        fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * plugins/MineAuth/config.json を一時ディレクトリに書く
         * 指定しない項目は MineAuthConfig の既定値になる
         *
         * @param port HTTP サーバーのポート
         * @return fukurou の serverFiles に渡すディレクトリ
         */
        fun writeServerFiles(port: Int): Path {
            val root = Files.createTempDirectory("mineauth-game-test")
            val configFile = root.resolve("plugins/MineAuth/config.json")
            Files.createDirectories(configFile.parent)
            val config = buildJsonObject {
                putJsonObject("server") {
                    put("baseUrl", baseUrlOf(port))
                    put("port", port)
                }
            }
            Files.writeString(configFile, config.toString())
            // サーバーの作り直しでも同じファイルをコピーするので、JVM の終了時に消す
            // deleteOnExit は登録と逆の順に消すため、親から登録して中身を先に消させる
            listOf(root, configFile.parent.parent, configFile.parent, configFile).forEach { it.toFile().deleteOnExit() }
            return root
        }
    }
}
