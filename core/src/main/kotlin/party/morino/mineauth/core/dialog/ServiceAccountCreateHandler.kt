package party.morino.mineauth.core.dialog

import arrow.core.Either
import com.github.shynixn.mccoroutine.bukkit.launch
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.repository.AccountError
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType

/**
 * サービスアカウント作成処理を行うハンドラ
 * ダイアログからの入力を受け取り、サービスアカウント作成処理を実行する
 */
object ServiceAccountCreateHandler : KoinComponent {
    private val plugin: MineAuth by inject()

    // サービス名の許可パターン（英数字、ハイフン、アンダースコア）
    private val SERVICE_NAME_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

    /**
     * サービスアカウント作成を処理する
     * @param player 実行したプレイヤー
     * @param serviceName サービス名
     */
    fun handleCreate(
        player: Player,
        serviceName: String
    ) {
        // コルーチンスコープで非同期処理を実行
        plugin.launch {
            // バリデーション
            val validationResult = validate(serviceName)
            if (validationResult is Either.Left) {
                player.sendRichMessage("<red>${validationResult.value}</red>")
                return@launch
            }

            // サービスアカウント作成
            val result = AccountRepository.create(AccountType.SERVICE, serviceName)

            // 結果の表示
            when (result) {
                is Either.Left -> handleError(player, result.value)
                is Either.Right -> handleSuccess(player, result.value.identifier)
            }
        }
    }

    /**
     * 入力値のバリデーション
     */
    private fun validate(serviceName: String): Either<String, Unit> {
        // サービス名のチェック
        if (serviceName.isBlank()) {
            return Either.Left("サービス名を入力してください")
        }
        if (serviceName.length > 64) {
            return Either.Left("サービス名は64文字以内で入力してください")
        }
        if (!SERVICE_NAME_PATTERN.matches(serviceName)) {
            return Either.Left("サービス名には英数字、ハイフン、アンダースコアのみ使用できます")
        }

        return Either.Right(Unit)
    }

    /**
     * エラー時の処理
     */
    private fun handleError(player: Player, error: AccountError) {
        val message = when (error) {
            is AccountError.AlreadyExists ->
                "同じ名前のサービスアカウントが既に存在します"
            is AccountError.DatabaseError ->
                "データベースエラーが発生しました: ${error.message}"
            is AccountError.NotFound ->
                "アカウントが見つかりません"
        }
        player.sendRichMessage("<red>$message</red>")
    }

    /**
     * 成功時の処理
     */
    private fun handleSuccess(player: Player, serviceName: String) {
        player.sendRichMessage("<green>サービスアカウントを作成しました！</green>")
        player.sendRichMessage("")
        player.sendRichMessage("<gray>サービス名:</gray> <white>$serviceName</white>")
        player.sendRichMessage("")
        player.sendRichMessage(
            "<yellow>トークンを発行するには <white>/mineauth service token $serviceName</white> を実行してください</yellow>"
        )
    }
}
