package party.morino.mineauth.core.commands

import arrow.core.Either
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.annotations.Argument
import org.incendo.cloud.annotations.Command
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.api.annotations.Permission
import party.morino.mineauth.core.dialog.ServiceAccountCreateDialog
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.utils.KeyUtils.getKeys
import party.morino.mineauth.core.model.ServiceName
import party.morino.mineauth.core.repository.AccountError
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType
import party.morino.mineauth.core.repository.ServiceAccountTokenRepository
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.*

/**
 * サービスアカウント管理コマンド
 * サービスアカウントの作成・一覧表示・削除・トークン発行を行う
 */
@Command("mineauth|ma")
class ServiceAccountCommand : KoinComponent {

    // サービスアカウントトークンの有効期間（1年）
    private val TOKEN_LIFETIME_MS = 365L * 24 * 3600 * 1000

    /**
     * サービスアカウント作成ダイアログを表示するコマンド
     * プレイヤーのみ実行可能
     */
    @Command("service create")
    @Permission("mineauth.service.create")
    fun createService(sender: CommandSender) {
        // プレイヤーチェック
        if (sender !is Player) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行可能です")
            return
        }

        // ダイアログを表示
        ServiceAccountCreateDialog.show(sender)
    }

    /**
     * 登録されているサービスアカウント一覧を表示する
     */
    @Command("service list")
    @Permission("mineauth.service.list")
    suspend fun listServices(sender: CommandSender) {
        val result = AccountRepository.findAllServiceAccounts()

        when (result) {
            is Either.Left -> {
                sender.sendRichMessage("<red>サービスアカウント一覧の取得に失敗しました</red>")
            }
            is Either.Right -> {
                val accounts = result.value
                if (accounts.isEmpty()) {
                    sender.sendRichMessage("<yellow>登録されているサービスアカウントはありません</yellow>")
                    return
                }

                sender.sendRichMessage("<gold>===== サービスアカウント一覧 =====</gold>")
                sender.sendRichMessage("<gray>合計: ${accounts.size}件</gray>")
                sender.sendRichMessage("")

                accounts.forEach { account ->
                    sender.sendRichMessage(
                        "<white>${account.identifier}</white> " +
                            "<gray>(ID: ${account.accountId})</gray>"
                    )
                }
            }
        }
    }

    /**
     * 指定されたサービスアカウントの詳細情報を表示する
     */
    @Command("service info <name>")
    @Permission("mineauth.service.info")
    suspend fun serviceInfo(
        sender: CommandSender,
        @Argument("name") name: ServiceName
    ) {
        val result = AccountRepository.findByIdentifier(AccountType.SERVICE, name.value)

        when (result) {
            is Either.Left -> {
                when (result.value) {
                    is AccountError.NotFound ->
                        sender.sendRichMessage("<red>サービスアカウントが見つかりません: ${name.value}</red>")
                    else ->
                        sender.sendRichMessage("<red>サービスアカウント情報の取得に失敗しました</red>")
                }
            }
            is Either.Right -> {
                val account = result.value
                sender.sendRichMessage("<gold>===== サービスアカウント詳細 =====</gold>")
                sender.sendRichMessage("")
                sender.sendRichMessage("<gray>サービス名:</gray> <white>${account.identifier}</white>")
                sender.sendRichMessage(
                    "<gray>アカウントID:</gray> <yellow><click:copy_to_clipboard:'${account.accountId}'>" +
                        "${account.accountId}</click></yellow> <dark_gray>(クリックでコピー)</dark_gray>"
                )

                // トークン情報を取得
                val tokensResult = ServiceAccountTokenRepository.findByAccountId(account.accountId)
                if (tokensResult is Either.Right) {
                    val tokens = tokensResult.value
                    val activeTokens = tokens.filter { !it.revoked }
                    sender.sendRichMessage("<gray>有効なトークン数:</gray> <white>${activeTokens.size}</white>")
                    activeTokens.forEach { token ->
                        val lastUsed = token.lastUsedAt?.toString() ?: "未使用"
                        sender.sendRichMessage("  <gray>ID:</gray> <white>${token.tokenId}</white>")
                        sender.sendRichMessage("  <gray>最終使用:</gray> <white>$lastUsed</white>")
                    }
                }
            }
        }
    }

    /**
     * 指定されたサービスアカウントを削除する
     * 関連する全トークンも失効させる
     */
    @Command("service delete <name>")
    @Permission("mineauth.service.delete")
    suspend fun deleteService(
        sender: CommandSender,
        @Argument("name") name: ServiceName
    ) {
        // まずアカウントを取得してIDを確認
        val accountResult = AccountRepository.findByIdentifier(AccountType.SERVICE, name.value)
        when (accountResult) {
            is Either.Left -> {
                sender.sendRichMessage("<red>サービスアカウントが見つかりません: ${name.value}</red>")
                return
            }
            is Either.Right -> {
                val account = accountResult.value

                // 関連する全トークンを失効
                ServiceAccountTokenRepository.revokeByAccountId(account.accountId)

                // アカウントを削除
                val deleteResult = AccountRepository.deleteByIdentifier(AccountType.SERVICE, name.value)
                when (deleteResult) {
                    is Either.Left -> {
                        sender.sendRichMessage("<red>サービスアカウントの削除に失敗しました</red>")
                    }
                    is Either.Right -> {
                        sender.sendRichMessage("<green>サービスアカウント「${name.value}」を削除しました</green>")
                    }
                }
            }
        }
    }

    /**
     * サービスアカウントのトークンを生成・再生成する
     * 既存のトークンがあれば失効させ、新しいトークンを発行する
     */
    @Command("service token <name>")
    @Permission("mineauth.service.token")
    suspend fun generateToken(
        sender: CommandSender,
        @Argument("name") name: ServiceName
    ) {
        // アカウントを取得
        val accountResult = AccountRepository.findByIdentifier(AccountType.SERVICE, name.value)
        val account = when (accountResult) {
            is Either.Left -> {
                sender.sendRichMessage("<red>サービスアカウントが見つかりません: ${name.value}</red>")
                return
            }
            is Either.Right -> accountResult.value
        }

        // 既存トークンを全て失効
        ServiceAccountTokenRepository.revokeByAccountId(account.accountId)

        // 新しいトークンを生成
        val jwtConfigData: JWTConfigData = get()
        val tokenId = UUID.randomUUID().toString()
        val now = Date()

        val token = JWT.create()
            .withIssuer(jwtConfigData.issuer)
            .withExpiresAt(Date(now.time + TOKEN_LIFETIME_MS))
            .withIssuedAt(now)
            .withJWTId(tokenId)
            .withClaim("account_id", account.accountId)
            .withClaim("account_type", "service")
            .withClaim("identifier", account.identifier)
            .withClaim("token_type", "service_token")
            .sign(
                Algorithm.RSA256(
                    getKeys().second as RSAPublicKey,
                    getKeys().first as RSAPrivateKey
                )
            )

        // トークンハッシュを計算してDBに保存
        val tokenHash = sha256(token)
        val createdBy = if (sender is Player) sender.uniqueId.toString() else "console"

        val saveResult = ServiceAccountTokenRepository.create(
            tokenId = tokenId,
            accountId = account.accountId,
            tokenHash = tokenHash,
            createdBy = createdBy
        )

        when (saveResult) {
            is Either.Left -> {
                sender.sendRichMessage("<red>トークンの保存に失敗しました</red>")
            }
            is Either.Right -> {
                sender.sendRichMessage("<green>サービスアカウントトークンを発行しました！</green>")
                sender.sendRichMessage("")
                sender.sendRichMessage(
                    "<gold><bold>⚠ 重要:</bold></gold> <white>トークンは一度しか表示されません！</white>"
                )
                sender.sendRichMessage(
                    "<gray>トークン:</gray> <yellow><click:copy_to_clipboard:'$token'>" +
                        "${token.take(20)}...</click></yellow> <dark_gray>(クリックでコピー)</dark_gray>"
                )
            }
        }
    }

    /**
     * 文字列のSHA-256ハッシュを計算する
     */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
