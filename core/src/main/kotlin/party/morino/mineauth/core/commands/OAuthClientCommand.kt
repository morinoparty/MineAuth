package party.morino.mineauth.core.commands

import arrow.core.Either
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.annotations.Argument
import org.incendo.cloud.annotations.Command
import org.incendo.cloud.annotations.Permission
import org.incendo.cloud.annotation.specifier.Greedy
import party.morino.mineauth.core.dialog.OAuthClientCreateDialog
import party.morino.mineauth.core.dialog.OAuthClientCreateHandler
import party.morino.mineauth.core.model.ClientId
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientData
import party.morino.mineauth.core.repository.OAuthClientError
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.utils.RedirectUriValidator

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
                sender.sendRichMessage("<gray>リダイレクトURI:</gray>")
                client.redirectUris.forEach { uri -> sender.sendRichMessage("  <white>$uri</white>") }
                sender.sendRichMessage(
                    "<gray>トークンイントロスペクション:</gray> <white>${introspectionStateLabel(client)}</white>"
                )
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

    /**
     * Confidentialクライアントにトークンイントロスペクションを許可する
     * 変更は次のリクエストから有効になる（クライアント認証のたびにDBから読み直すため）
     */
    @Command("client introspection allow <clientId>")
    @Permission(OAuthClientCreateHandler.INTROSPECTION_PERMISSION)
    suspend fun allowIntrospection(
        sender: CommandSender,
        @Argument("clientId") clientId: ClientId
    ) {
        updateIntrospectionAllowed(sender, clientId, allowed = true)
    }

    /**
     * Confidentialクライアントのトークンイントロスペクションを拒否する
     * 変更は次のリクエストから有効になる（クライアント認証のたびにDBから読み直すため）
     */
    @Command("client introspection deny <clientId>")
    @Permission(OAuthClientCreateHandler.INTROSPECTION_PERMISSION)
    suspend fun denyIntrospection(
        sender: CommandSender,
        @Argument("clientId") clientId: ClientId
    ) {
        updateIntrospectionAllowed(sender, clientId, allowed = false)
    }

    /**
     * トークンイントロスペクションの利用権限を更新し、結果を通知する共通処理
     *
     * @param sender 実行者（結果を通知する）
     * @param clientId 対象クライアント
     * @param allowed 許可する場合true
     */
    private suspend fun updateIntrospectionAllowed(
        sender: CommandSender,
        clientId: ClientId,
        allowed: Boolean
    ) {
        val result = OAuthClientRepository.setIntrospectionAllowed(clientId.value, allowed)

        when (result) {
            is Either.Left -> {
                when (result.value) {
                    is OAuthClientError.NotFound ->
                        sender.sendRichMessage("<red>クライアントが見つかりません: ${clientId.value}</red>")
                    is OAuthClientError.InvalidCredentials ->
                        sender.sendRichMessage("<red>このクライアントはPublicタイプのため、トークンイントロスペクションの権限を持てません</red>")
                    else ->
                        sender.sendRichMessage("<red>トークンイントロスペクションの権限の更新に失敗しました</red>")
                }
            }
            is Either.Right -> {
                val state = if (allowed) "許可" else "拒否"
                sender.sendRichMessage(
                    "<green>クライアント「${result.value.clientName}」のトークンイントロスペクションを${state}しました</green>"
                )
            }
        }
    }

    /**
     * client info に表示するトークンイントロスペクションの権限の状態を返す
     *
     * @param client 対象クライアント
     * @return 表示用の文字列
     */
    private fun introspectionStateLabel(client: OAuthClientData): String = when {
        // Publicクライアントはイントロスペクションエンドポイントで認証できないため、権限の対象にならない
        client.clientType != ClientType.CONFIDENTIAL -> "対象外（Publicクライアント）"
        client.introspectionAllowed -> "許可"
        else -> "拒否"
    }

    /**
     * OAuthクライアントにリダイレクトURIを追加する
     * URIには : や / が含まれるため、残りの引数をまとめて1つのURIとして受け取る
     */
    @Command("client redirect add <clientId> <uri>")
    @Permission("mineauth.client.redirect")
    suspend fun addRedirectUri(
        sender: CommandSender,
        @Argument("clientId") clientId: ClientId,
        @Argument("uri") @Greedy uri: String
    ) {
        updateRedirectUris(sender, clientId) { current ->
            // 既に登録済みなら一覧を変えずにエラーにする
            if (uri in current) {
                Either.Left("このリダイレクトURIは登録済みです: $uri")
            } else {
                // 追加するURIだけを検証する（以前の規則で登録された値が残っていても追加できるようにする）
                RedirectUriValidator.validateAddition(current, uri.trim()).mapLeft { it.message }
            }
        }
    }

    /**
     * OAuthクライアントからリダイレクトURIを削除する
     * 認可できないクライアントが生まれないよう、最後の1件は削除できない
     */
    @Command("client redirect remove <clientId> <uri>")
    @Permission("mineauth.client.redirect")
    suspend fun removeRedirectUri(
        sender: CommandSender,
        @Argument("clientId") clientId: ClientId,
        @Argument("uri") @Greedy uri: String
    ) {
        updateRedirectUris(sender, clientId) { current ->
            when {
                uri !in current -> Either.Left("このリダイレクトURIは登録されていません: $uri")
                current.size == 1 -> Either.Left("最後のリダイレクトURIは削除できません")
                else -> Either.Right(current - uri)
            }
        }
    }

    /**
     * リダイレクトURIの一覧を読み出し、変更して保存する共通処理
     *
     * @param sender 実行者（結果を通知する）
     * @param clientId 対象クライアント
     * @param transform 現在の一覧から新しい一覧を作る関数（失敗時はエラーメッセージ）
     */
    private suspend fun updateRedirectUris(
        sender: CommandSender,
        clientId: ClientId,
        transform: (List<String>) -> Either<String, List<String>>
    ) {
        val client = when (val found = OAuthClientRepository.findById(clientId.value)) {
            is Either.Left -> {
                sender.sendRichMessage("<red>クライアントが見つかりません: ${clientId.value}</red>")
                return
            }
            is Either.Right -> found.value
        }

        val newUris = when (val transformed = transform(client.redirectUris)) {
            is Either.Left -> {
                // 拒否理由には入力されたURIが含まれるため、MiniMessageのタグとして解釈させない
                sender.sendRichMessage("<red>${MiniMessage.miniMessage().escapeTags(transformed.value)}</red>")
                return
            }
            is Either.Right -> transformed.value
        }

        when (val updated = OAuthClientRepository.update(clientId.value, redirectUris = newUris)) {
            is Either.Left -> sender.sendRichMessage("<red>リダイレクトURIの更新に失敗しました</red>")
            is Either.Right -> {
                sender.sendRichMessage("<green>クライアント「${updated.value.clientName}」のリダイレクトURIを更新しました</green>")
                updated.value.redirectUris.forEach { uri -> sender.sendRichMessage("  <white>$uri</white>") }
            }
        }
    }
}
