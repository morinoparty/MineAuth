package party.morino.mineauth.core.commands.parser

import org.bukkit.command.CommandSender
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.context.CommandInput
import org.incendo.cloud.parser.ArgumentParseResult
import org.incendo.cloud.parser.ArgumentParser
import org.incendo.cloud.parser.ParserDescriptor
import org.incendo.cloud.suggestion.BlockingSuggestionProvider
import party.morino.mineauth.core.model.ClientId
import party.morino.mineauth.core.repository.OAuthClientRepository

/**
 * OAuthクライアントIDをパースするコマンドパーサー
 * Tab補完でDBから取得したクライアントID一覧を表示する
 */
class ClientIdParser<C> :
    ArgumentParser<C, ClientId>,
    BlockingSuggestionProvider.Strings<CommandSender> {

    /**
     * 入力文字列をClientIdにパースする
     * DBに存在するクライアントIDのみ許可
     */
    override fun parse(
        commandContext: CommandContext<C & Any>,
        commandInput: CommandInput
    ): ArgumentParseResult<ClientId> {
        // 入力からクライアントIDを読み取り
        val inputId = commandInput.readString()
        val clientIds = OAuthClientRepository.getAllClientIdsBlocking()

        // 入力されたIDがDBに存在するか確認
        return if (clientIds.contains(inputId)) {
            ArgumentParseResult.success(ClientId(inputId))
        } else {
            ArgumentParseResult.failure(Throwable("Client ID '$inputId' not found"))
        }
    }

    /**
     * Tab補完用のサジェストを提供
     * DBから全クライアントIDを取得して返す
     */
    override fun stringSuggestions(
        commandContext: CommandContext<CommandSender?>,
        input: CommandInput
    ): Iterable<String> = OAuthClientRepository.getAllClientIdsBlocking()

    companion object {
        /**
         * ParserDescriptorを生成するファクトリメソッド
         */
        fun clientIdParser(): ParserDescriptor<CommandSender, ClientId> =
            ParserDescriptor.of(ClientIdParser(), ClientId::class.java)
    }
}
