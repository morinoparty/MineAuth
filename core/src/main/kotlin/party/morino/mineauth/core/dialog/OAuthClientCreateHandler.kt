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
import java.net.URI

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
     * @param redirectUri リダイレクトURI
     */
    fun handleCreate(
        player: Player,
        clientName: String,
        clientType: String,
        redirectUri: String
    ) {
        // コルーチンスコープで非同期処理を実行
        plugin.launch {
            // バリデーション
            val validationResult = validate(clientName, clientType, redirectUri)
            if (validationResult is Either.Left) {
                player.sendRichMessage("<red>${validationResult.value}</red>")
                return@launch
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
                redirectUri = redirectUri,
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
     */
    private fun validate(
        clientName: String,
        clientType: String,
        redirectUri: String
    ): Either<String, Unit> {
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

        // リダイレクトURIのチェック
        if (redirectUri.isBlank()) {
            return Either.Left("リダイレクトURIを入力してください")
        }
        if (!isValidRedirectUri(redirectUri)) {
            return Either.Left("無効なリダイレクトURI形式です")
        }

        return Either.Right(Unit)
    }

    /**
     * リダイレクトURIの形式チェック
     */
    private fun isValidRedirectUri(uri: String): Boolean {
        return try {
            val parsed = URI(uri)
            // スキームとホストが存在することを確認
            parsed.scheme != null && parsed.host != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * エラー時の処理
     */
    private fun handleError(player: Player, error: OAuthClientError) {
        val message = when (error) {
            is OAuthClientError.IssuerAccountNotFound ->
                "アカウントが見つかりません。先に /mineauth register を実行してください"
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
        player.sendRichMessage("<gray>リダイレクトURI:</gray> <white>${result.client.redirectUri}</white>")
        player.sendRichMessage("<gray>クライアント種別:</gray> <white>${result.client.clientType.value}</white>")
    }
}
