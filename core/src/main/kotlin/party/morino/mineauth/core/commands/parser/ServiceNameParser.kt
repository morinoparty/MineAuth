package party.morino.mineauth.core.commands.parser

import kotlinx.coroutines.runBlocking
import org.bukkit.command.CommandSender
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.context.CommandInput
import org.incendo.cloud.parser.ArgumentParseResult
import org.incendo.cloud.parser.ArgumentParser
import org.incendo.cloud.parser.ParserDescriptor
import org.incendo.cloud.suggestion.BlockingSuggestionProvider
import party.morino.mineauth.core.model.ServiceName
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType

/**
 * サービスアカウント名をパースするコマンドパーサー
 * Tab補完でDBから取得したサービスアカウント名一覧を表示する
 */
class ServiceNameParser<C> :
    ArgumentParser<C, ServiceName>,
    BlockingSuggestionProvider.Strings<CommandSender> {

    /**
     * 入力文字列をServiceNameにパースする
     * DBに存在するサービスアカウント名のみ許可
     */
    override fun parse(
        commandContext: CommandContext<C & Any>,
        commandInput: CommandInput
    ): ArgumentParseResult<ServiceName> {
        val inputName = commandInput.readString()
        val serviceNames = getAllServiceNamesBlocking()

        // 入力された名前がDBに存在するか確認
        return if (serviceNames.contains(inputName)) {
            ArgumentParseResult.success(ServiceName(inputName))
        } else {
            ArgumentParseResult.failure(Throwable("Service account '$inputName' not found"))
        }
    }

    /**
     * Tab補完用のサジェストを提供
     * DBから全サービスアカウント名を取得して返す
     */
    override fun stringSuggestions(
        commandContext: CommandContext<CommandSender?>,
        input: CommandInput
    ): Iterable<String> = getAllServiceNamesBlocking()

    /**
     * ブロッキングで全サービスアカウント名を取得する
     */
    private fun getAllServiceNamesBlocking(): List<String> = runBlocking {
        AccountRepository.findAllServiceAccounts().fold(
            ifLeft = { emptyList() },
            ifRight = { accounts -> accounts.map { it.identifier } }
        )
    }

    companion object {
        /**
         * ParserDescriptorを生成するファクトリメソッド
         */
        fun serviceNameParser(): ParserDescriptor<CommandSender, ServiceName> =
            ParserDescriptor.of(ServiceNameParser(), ServiceName::class.java)
    }
}
