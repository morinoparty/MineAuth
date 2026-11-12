package party.morino.mineauth.core.dialog

import arrow.core.Either
import com.github.shynixn.mccoroutine.bukkit.launch
import net.kyori.adventure.text.minimessage.MiniMessage
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

    // トークンイントロスペクションの権限を変更するための権限ノード
    // 作成時の選択にも同じノードを要求し、mineauth.client.create だけで権限を自己付与できないようにする
    internal const val INTROSPECTION_PERMISSION = "mineauth.client.introspection"

    /**
     * クライアント作成を処理する
     * @param player 実行したプレイヤー
     * @param clientName クライアント名
     * @param clientType クライアント種別（"public" or "confidential"）
     * @param redirectUriInput リダイレクトURIの入力値（改行区切りで複数指定できる）
     * @param introspectionAllowed トークンイントロスペクションを許可するか（Confidentialクライアントのみ選択できる）
     */
    fun handleCreate(
        player: Player,
        clientName: String,
        clientType: String,
        redirectUriInput: String,
        introspectionAllowed: Boolean
    ) {
        // コルーチンスコープで非同期処理を実行
        plugin.launch {
            // バリデーション（成功時は整形済みのリダイレクトURI一覧を得る）
            val redirectUris = when (
                val validationResult = validate(
                    clientName,
                    clientType,
                    redirectUriInput,
                    introspectionAllowed,
                    // ダイアログの応答はクライアントが組み立てるため、選択肢の表示制御だけに頼らずここで判定する
                    canGrantIntrospection = player.hasPermission(INTROSPECTION_PERMISSION)
                )
            ) {
                is Either.Left -> {
                    // 拒否理由には入力されたURIが含まれるため、MiniMessageのタグとして解釈させない
                    player.sendRichMessage("<red>${MiniMessage.miniMessage().escapeTags(validationResult.value)}</red>")
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
                issuerAccountId = account.accountId,
                introspectionAllowed = introspectionAllowed
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
     * @param canGrantIntrospection 実行者がトークンイントロスペクションを許可できるか
     * @return 成功時は整形済みのリダイレクトURI一覧、失敗時はエラーメッセージ
     */
    internal fun validate(
        clientName: String,
        clientType: String,
        redirectUriInput: String,
        introspectionAllowed: Boolean,
        canGrantIntrospection: Boolean
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

        // Publicクライアントはイントロスペクションエンドポイントで認証できないため、権限を持てない
        // 黙って無視すると許可したつもりになるので、作成せずに理由を伝える
        if (introspectionAllowed && ClientType.fromValue(clientType) != ClientType.CONFIDENTIAL) {
            return Either.Left(
                "トークンイントロスペクションはConfidentialクライアントにのみ許可できます。" +
                    "種別をConfidentialにするか、チェックを外してください"
            )
        }

        // 許可するコマンドと同じ権限ノードを要求する（作成権限だけで個別認可を迂回させない）
        if (introspectionAllowed && !canGrantIntrospection) {
            return Either.Left("トークンイントロスペクションを許可するには $INTROSPECTION_PERMISSION 権限が必要です")
        }

        // リダイレクトURIのチェック（1件ずつ検証し、拒否理由をそのまま表示する）
        return RedirectUriValidator.parseAndValidate(redirectUriInput).mapLeft { it.message }
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

        // 権限を持てるのはConfidentialクライアントだけなので、Publicクライアントには表示しない
        if (result.client.clientType == ClientType.CONFIDENTIAL) {
            val state = if (result.client.introspectionAllowed) "許可" else "拒否"
            player.sendRichMessage("<gray>トークンイントロスペクション:</gray> <white>$state</white>")
        }
    }
}
