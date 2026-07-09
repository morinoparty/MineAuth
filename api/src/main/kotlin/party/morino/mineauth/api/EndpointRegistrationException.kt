package party.morino.mineauth.api

/**
 * エンドポイント登録の検証に失敗したときにスローされる例外
 * 発生した全ての検証エラーを[errors]として保持する（all-or-nothing登録のため、1つでも失敗すれば何もマウントされない）
 *
 * @property errors 検証エラーの全リスト
 */
class EndpointRegistrationException(
    val errors: List<RegistrationError>
) : IllegalArgumentException(
    "MineAuth endpoint registration failed (${errors.size} error(s)):\n" +
        errors.joinToString("\n") { "  - ${it.describe()}" }
)
