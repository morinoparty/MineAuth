package party.morino.mineauth.core.commands

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.incendo.cloud.annotations.Command
import org.incendo.cloud.annotations.Permission
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.load.FileUtils
import party.morino.mineauth.core.file.load.config.ConfigParseException
import party.morino.mineauth.core.web.WebServer
import java.util.logging.Level

/**
 * 設定とデータベース接続を読み直し、HTTPサーバーを再起動するコマンド
 *
 * 読み直しに失敗してもHTTPサーバーは必ず再開し、OAuthエンドポイントとプラグインAPIを止めたままにしない。
 * ConfigLoaderは検証に通った設定だけをKoinへ登録するため、不正なconfig.jsonでは以前の設定のまま再開される。
 */
@Command("mineauth|ma|mauth")
class ReloadCommand : KoinComponent {
    private val plugin: MineAuth by inject()

    @Command("reload")
    @Permission("moripaapi.command.reload")
    fun reload(sender: CommandSender) {
        sender.sendRichMessage("<gray>Reloading...</gray>")

        var settingsReloaded = false
        try {
            settingsReloaded = reloadSettings(sender)
        } finally {
            // 下のcatchが受け止めない例外（キャンセルやError）でも、サーバーを止めたままにしない
            val serverStarted = startServer(sender)
            if (settingsReloaded && serverStarted) {
                sender.sendRichMessage("<green>Reloaded</green>")
            }
        }
    }

    /**
     * HTTPサーバーを止めて、設定ファイルとデータベース接続を読み直す
     *
     * KeyUtilsが読み込み時にjwks.jsonを書き換えるため、リクエストが書きかけのファイルを読まないよう先に止める。
     *
     * @param sender 結果の報告先
     * @return 全て読み直せた場合はtrue（失敗の場合は報告済みでfalse）
     */
    private fun reloadSettings(sender: CommandSender): Boolean {
        // 失敗したときに、新しい設定が登録されたかどうかで報告を分けるために控えておく
        val previousConfig = getKoin().getOrNull<MineAuthConfig>()
        return try {
            WebServer.stopServer()
            sender.sendRichMessage("<gray>Server stopped</gray>")

            FileUtils.loadFiles()
            sender.sendRichMessage("<gray>Files reloaded</gray>")

            FileUtils.settingDatabase()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // サーバーを再開するための境界なので、想定外の例外も含めて受け止め、握りつぶさずに報告する
            reportReloadFailure(sender, e, configReplaced = getKoin().getOrNull<MineAuthConfig>() !== previousConfig)
            false
        }
    }

    /**
     * 読み直しの失敗をログと実行者へ報告する
     *
     * @param sender 報告先
     * @param cause 失敗の原因
     * @param configReplaced 失敗する前に新しいconfig.jsonが登録されていた場合はtrue
     */
    private fun reportReloadFailure(sender: CommandSender, cause: Exception, configReplaced: Boolean) {
        logFailure(
            "/mineauth reload failed. The HTTP server is started again with the settings that are registered now.",
            cause
        )
        sender.sendRichMessage("<red>Reload failed: ${describe(cause)}")
        // 以前の設定が残っているのはconfig.jsonが登録される前に失敗した場合だけなので、実際の状態に合わせて伝える
        val state = if (configReplaced) {
            "The new config.json is active, but the database connection stays on the previous settings."
        } else {
            "The previous settings stay active."
        }
        sender.sendRichMessage("<yellow>$state Fix the problem and run /mineauth reload again.</yellow>")
    }

    /**
     * 現在登録されている設定でHTTPサーバーを作り直して起動する
     *
     * 停止済みのサーバーは再利用できないので、作り直しに失敗した場合は起動も試みない。
     *
     * @param sender 結果の報告先
     * @return 起動できた場合はtrue（失敗の場合は報告済みでfalse）
     */
    private fun startServer(sender: CommandSender): Boolean = try {
        WebServer.settingServer()
        WebServer.startServer()
        sender.sendRichMessage("<gray>Server started</gray>")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFailure(
            "The HTTP server could not be started after /mineauth reload. OAuth endpoints and plugin APIs are unavailable.",
            e
        )
        sender.sendRichMessage("<red>The HTTP server could not be started: ${describe(e)}")
        sender.sendRichMessage(
            "<yellow>OAuth endpoints and plugin APIs are unavailable. " +
                "Fix the problem and run /mineauth reload again, or restart the server.</yellow>"
        )
        false
    }

    /**
     * 失敗をSEVEREでログに残す
     *
     * パーサーの例外は、2行目以降にエラー位置の前後のJSON input（データベースのパスワードやpepperを含みうる）を持つ。
     * config.jsonの解釈の失敗はConfigLoaderが1行目（オフセットとJSONパス）だけのConfigParseExceptionに置き換えているので、
     * 起動時のログと同じそのメッセージだけを残す。設定の誤りなので、スタックトレースは調査の役に立たない。
     * ConfigLoaderを経由しないパーサーの例外も、念のため入力の引用を伏せた1行目だけを残し、例外を原因として添付しない。
     * それ以外の例外は調査できるようスタックトレースごと残す。
     *
     * @param message 失敗の説明
     * @param cause 失敗の原因
     */
    private fun logFailure(message: String, cause: Exception) {
        when (cause) {
            is ConfigParseException -> plugin.logger.severe("$message ${cause.message}")
            is SerializationException -> plugin.logger.severe(
                "$message ${cause.javaClass.name}: ${ConfigParseException.summaryOf(cause)} " +
                    "(The rest of the parser message is not logged because it can quote the parsed file.)"
            )
            else -> plugin.logger.log(Level.SEVERE, message, cause)
        }
    }

    /**
     * 例外の内容をチャットへ送れる形にする
     *
     * 2行目以降にはconfig.jsonの抜粋（シークレットを含みうるJSON input）が入ることがあるため1行目だけを使い、
     * パーサーの例外では1行目に引用された入力も伏せる。
     * 例外の文字列がタグとして解釈されないようエスケープする。末尾の「\」で閉じタグが無効にならないよう、呼び出し側は直後に閉じタグを置かない。
     *
     * @param cause 例外
     * @return MiniMessageに埋め込める1行の説明
     */
    private fun describe(cause: Exception): String {
        val firstLine = when (cause) {
            is SerializationException -> ConfigParseException.summaryOf(cause)
            else -> ConfigParseException.firstLineOf(cause)
        }
        val summary = firstLine ?: cause.javaClass.simpleName
        return MiniMessage.miniMessage().escapeTags(summary)
    }
}
