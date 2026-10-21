package party.morino.mineauth.core.dialog

import arrow.core.Either
import com.github.shynixn.mccoroutine.bukkit.launch
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.OAuthClientError
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.utils.RedirectUriValidator

/**
 * OAuthクライアント作成処理を行うハンドラ
 * ダイアログからの入力を受け取り、クライアント作成処理を実行する
 */
object OAuthClientCreateHandler : KoinComponent {
    private val plugin: MineAuth by inject()

    /**
     * クライアント作成を処理する
     * @param player 実行したプレイヤー
     * @param clientName クライアント名
     * @param clientType クライアント種別（"public" or "confidential"）
     * @param redirectUriInput リダイレクトURIの入力値（改行区切りで複数指定できる）
     */
    fun handleCreate(
        player: Player,
        clientName: String,
        clientType: String,
        redirectUriInput: String
    ) {
        // コルーチンスコープで非同期処理を実行
        plugin.launch {
            // バリデーション（成功時は整形済みのリダイレクトURI一覧を得る）
            val redirectUris = when (val validationResult = validate(clientName, clientType, redirectUriInput)) {
                is Either.Left -> {
                    player.sendRichMessage("<red>${validationResult.value}</red>")
                    return@launch
                }
                is Either.Right -> validationResult.value
            }

            // プレイヤーのアカウントを取得または作成
            val accountResult = AccountRepository.getOrCreatePlayerAccount(player.uniqueId)
            val account = when (accountResult) {
                is Either.Left -> {
                    player.sendRichMessage("<red>アカウントの取得に失敗しました</red>")
                    return@launch
                }
                is Either.Right -> accountResult.value
            }

            // クライアントタイプの変換
            val type = ClientType.fromValue(clientType) ?: ClientType.PUBLIC

            // クライアント作成
            val result = OAuthClientRepository.create(
                clientName = clientName,
                clientType = type,
                redirectUris = redirectUris,
                issuerAccountId = account.accountId
            )

            // 結果の表示
            when (result) {
                is Either.Left -> handleError(player, result.value)
                is Either.Right -> handleSuccess(player, result.value)
            }
        }
    }

    /**
     * 入力値のバリデーション
     *
     * @return 成功時は整形済みのリダイレクトURI一覧、失敗時はエラーメッセージ
     */
    private fun validate(
        clientName: String,
        clientType: String,
        redirectUriInput: String
    ): Either<String, List<String>> {
        // クライアント名のチェック
        if (clientName.isBlank()) {
            return Either.Left("クライアント名を入力してください")
        }
        if (clientName.length > 255) {
            return Either.Left("クライアント名は255文字以内で入力してください")
        }

        // クライアント種別のチェック
        if (ClientType.fromValue(clientType) == null) {
            return Either.Left("無効なクライアント種別です")
        }

        // リダイレクトURIのチェック（1件ずつ検証する）
        return RedirectUriValidator.parseAndValidate(redirectUriInput)
    }

    /**
     * エラー時の処理
     */
    private fun handleError(player: Player, error: OAuthClientError) {
        val message = when (error) {
            is OAuthClientError.IssuerAccountNotFound ->
                "アカウントが見つかりません。先に <click:suggest_command:'/ma register'>/ma register</click> を実行してください"
            is OAuthClientError.DatabaseError ->
                "データベースエラーが発生しました: ${error.message}"
            else -> "クライアントの作成に失敗しました"
        }
        player.sendRichMessage("<red>$message</red>")
    }

    /**
     * 成功時の処理
     */
    private fun handleSuccess(player: Player, result: OAuthClientCreationResult) {
        player.sendRichMessage("<green>OAuthクライアントを作成しました！</green>")
        player.sendRichMessage("")
        player.sendRichMessage("<gray>クライアント名:</gray> <white>${result.client.clientName}</white>")
        player.sendRichMessage(
            "<gray>クライアントID:</gray> <yellow><click:copy_to_clipboard:'${result.client.clientId}'>" +
                "${result.client.clientId}</click></yellow> <gray>(クリックでコピー)</gray>"
        )

        // Confidentialクライアントの場合はシークレットも表示
        result.clientSecret?.let { secret ->
            player.sendRichMessage("")
            player.sendRichMessage(
                "<gold><bold>⚠ 重要:</bold></gold> <white>クライアントシークレットは一度しか表示されません！</white>"
            )
            player.sendRichMessage(
                "<gray>クライアントシークレット:</gray> <yellow><click:copy_to_clipboard:'$secret'>" +
                    "$secret</click></yellow> <gray>(クリックでコピー)</gray>"
            )
        }

        player.sendRichMessage("")
        player.sendRichMessage("<gray>リダイレクトURI:</gray>")
        result.client.redirectUris.forEach { uri -> player.sendRichMessage("  <white>$uri</white>") }
        player.sendRichMessage("<gray>クライアント種別:</gray> <white>${result.client.clientType.value}</white>")
    }
}
