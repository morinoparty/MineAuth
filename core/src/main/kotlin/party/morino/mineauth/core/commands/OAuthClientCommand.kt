package party.morino.mineauth.core.commands

import arrow.core.Either
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.annotations.Argument
import org.incendo.cloud.annotations.Command
import party.morino.mineauth.api.annotations.Permission
import party.morino.mineauth.core.dialog.OAuthClientCreateDialog
import party.morino.mineauth.core.model.ClientId
import party.morino.mineauth.core.repository.OAuthClientError
import party.morino.mineauth.core.repository.OAuthClientRepository

/**
 * OAuthクライアント管理コマンド
 * クライアントの作成・一覧表示・削除などを行う
 */
@Command("mineauth|ma")
class OAuthClientCommand {

    /**
     * OAuthクライアント作成ダイアログを表示するコマンド
     * プレイヤーのみ実行可能
     */
    @Command("client create")
    @Permission("mineauth.client.create")
    fun createClient(sender: CommandSender) {
        // プレイヤーチェック
        if (sender !is Player) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行可能です")
            return
        }

        // ダイアログを表示
        OAuthClientCreateDialog.show(sender)
    }

    /**
     * 登録されているOAuthクライアント一覧を表示する
     */
    @Command("client list")
    @Permission("mineauth.client.list")
    suspend fun listClients(sender: CommandSender) {
        val result = OAuthClientRepository.findAll()

        when (result) {
            is Either.Left -> {
                sender.sendRichMessage("<red>クライアント一覧の取得に失敗しました</red>")
            }
            is Either.Right -> {
                val clients = result.value
                if (clients.isEmpty()) {
                    sender.sendRichMessage("<yellow>登録されているクライアントはありません</yellow>")
                    return
                }

                sender.sendRichMessage("<gold>===== OAuthクライアント一覧 =====</gold>")
                sender.sendRichMessage("<gray>合計: ${clients.size}件</gray>")
                sender.sendRichMessage("")

                clients.forEach { client ->
                    // クライアントIDをクリックでコピー可能に
                    sender.sendRichMessage(
                        "<white>${client.clientName}</white> " +
                            "<gray>(${client.clientType.value})</gray>"
                    )
                    sender.sendRichMessage(
                        "  <gray>ID:</gray> <yellow><click:copy_to_clipboard:'${client.clientId}'>" +
                            "${client.clientId}</click></yellow> <dark_gray>(クリックでコピー)</dark_gray>"
                    )
                }
            }
        }
    }

    /**
     * 指定されたOAuthクライアントの詳細情報を表示する
     */
    @Command("client info <clientId>")
    @Permission("mineauth.client.info")
    suspend fun clientInfo(
        sender: CommandSender,
        @Argument("clientId") clientId: ClientId
    ) {
        val result = OAuthClientRepository.findById(clientId.value)

        when (result) {
            is Either.Left -> {
                when (result.value) {
                    is OAuthClientError.NotFound ->
                        sender.sendRichMessage("<red>クライアントが見つかりません: ${clientId.value}</red>")
                    else ->
                        sender.sendRichMessage("<red>クライアント情報の取得に失敗しました</red>")
                }
            }
            is Either.Right -> {
                val client = result.value
                sender.sendRichMessage("<gold>===== クライアント詳細 =====</gold>")
                sender.sendRichMessage("")
                sender.sendRichMessage("<gray>クライアント名:</gray> <white>${client.clientName}</white>")
                sender.sendRichMessage(
                    "<gray>クライアントID:</gray> <yellow><click:copy_to_clipboard:'${client.clientId}'>" +
                        "${client.clientId}</click></yellow> <dark_gray>(クリックでコピー)</dark_gray>"
                )
                sender.sendRichMessage("<gray>クライアント種別:</gray> <white>${client.clientType.value}</white>")
                sender.sendRichMessage("<gray>リダイレクトURI:</gray> <white>${client.redirectUri}</white>")
                sender.sendRichMessage("<gray>作成日時:</gray> <white>${client.createdAt}</white>")
                sender.sendRichMessage("<gray>更新日時:</gray> <white>${client.updatedAt}</white>")
            }
        }
    }

    /**
     * 指定されたOAuthクライアントを削除する
     */
    @Command("client delete <clientId>")
    @Permission("mineauth.client.delete")
    suspend fun deleteClient(
        sender: CommandSender,
        @Argument("clientId") clientId: ClientId
    ) {
        // 削除前に存在確認と名前取得
        val existingResult = OAuthClientRepository.findById(clientId.value)
        val clientName = when (existingResult) {
            is Either.Right -> existingResult.value.clientName
            else -> clientId.value
        }

        val result = OAuthClientRepository.delete(clientId.value)

        when (result) {
            is Either.Left -> {
                when (result.value) {
                    is OAuthClientError.NotFound ->
                        sender.sendRichMessage("<red>クライアントが見つかりません: ${clientId.value}</red>")
                    else ->
                        sender.sendRichMessage("<red>クライアントの削除に失敗しました</red>")
                }
            }
            is Either.Right -> {
                sender.sendRichMessage("<green>クライアント「$clientName」を削除しました</green>")
            }
        }
    }

    /**
     * Confidentialクライアントのシークレットを再生成する
     */
    @Command("client regenerate-secret <clientId>")
    @Permission("mineauth.client.regenerate")
    suspend fun regenerateSecret(
        sender: CommandSender,
        @Argument("clientId") clientId: ClientId
    ) {
        val result = OAuthClientRepository.resetClientSecret(clientId.value)

        when (result) {
            is Either.Left -> {
                when (result.value) {
                    is OAuthClientError.NotFound ->
                        sender.sendRichMessage("<red>クライアントが見つかりません: ${clientId.value}</red>")
                    is OAuthClientError.InvalidCredentials ->
                        sender.sendRichMessage("<red>このクライアントはPublicタイプのため、シークレットを持ちません</red>")
                    else ->
                        sender.sendRichMessage("<red>シークレットの再生成に失敗しました</red>")
                }
            }
            is Either.Right -> {
                val newSecret = result.value
                sender.sendRichMessage("<green>クライアントシークレットを再生成しました！</green>")
                sender.sendRichMessage("")
                sender.sendRichMessage(
                    "<gold><bold>⚠ 重要:</bold></gold> <white>クライアントシークレットは一度しか表示されません！</white>"
                )
                sender.sendRichMessage(
                    "<gray>新しいシークレット:</gray> <yellow><click:copy_to_clipboard:'$newSecret'>" +
                        "$newSecret</click></yellow> <dark_gray>(クリックでコピー)</dark_gray>"
                )
            }
        }
    }
}
