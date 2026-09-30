package party.morino.mineauth.core.utils

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import party.morino.mineauth.core.file.data.PasswordConfig

/**
 * プレイヤーのログインパスワードをArgon2idでハッシュ化・検証する
 *
 * password4jのpsw4j.propertiesはPaperのクラスローダー環境によって読み込まれないことがあり、
 * その場合pepperやArgon2パラメータが暗黙のデフォルト値に変わって既存ハッシュと一致しなくなる。
 * そのため設定値はすべてconfig.jsonで明示し、プロパティファイルには依存しない。
 *
 * @property config config.jsonのパスワードハッシュ設定（pepperは設定済みであること）
 */
class PlayerPasswordHasher(private val config: PasswordConfig) {
    // ConfigLoaderで生成・保存済みのpepper
    private val pepper: String = requireNotNull(config.pepper) { "Password pepper is not configured" }

    // 新規ハッシュ生成用のArgon2idパラメータ
    private val argon2Function: Argon2Function = Argon2Function.getInstance(
        config.memory,
        config.iterations,
        config.parallelism,
        config.hashLength,
        Argon2.ID,
    )

    /**
     * パスワードをpepper付きArgon2idでハッシュ化する
     *
     * @param password ハッシュ化する平文のパスワード
     * @return パラメータとソルトを含むArgon2idハッシュ文字列
     */
    fun hash(password: String): String {
        return Password.hash(password)
            .addRandomSalt(config.saltLength)
            .addPepper(pepper)
            .with(argon2Function)
            .result
    }

    /**
     * パスワードが保存済みハッシュと一致するかを検証する
     *
     * Argon2のパラメータはハッシュ文字列から復元するため、生成時のパラメータに関係なく検証できる。
     * 現在のpepperで一致しない場合は、旧バージョンのpepperでも検証する。
     *
     * @param password 検証する平文のパスワード
     * @param hashedPassword 保存されているArgon2idハッシュ文字列
     * @return 検証結果（旧pepperや旧パラメータで一致した場合は再ハッシュが必要）
     */
    fun verify(password: String, hashedPassword: String): PasswordVerification {
        // 検証にはハッシュ生成時と同じパラメータが必要なので、ハッシュ文字列から復元する
        val function = Argon2Function.getInstanceFromHash(hashedPassword)
        // 現在のpepperで一致した場合、パラメータが現在の設定と異なれば再ハッシュ対象にする
        if (Password.check(password, hashedPassword).addPepper(pepper).with(function)) {
            return if (function == argon2Function) {
                PasswordVerification.MATCHED
            } else {
                PasswordVerification.MATCHED_NEEDS_REHASH
            }
        }
        // 旧バージョンのpepperで生成されたハッシュへのフォールバック
        val matchedLegacy = LEGACY_PEPPERS.any { legacyPepper ->
            Password.check(password, hashedPassword).addPepper(legacyPepper).with(function)
        }
        return if (matchedLegacy) PasswordVerification.MATCHED_NEEDS_REHASH else PasswordVerification.NOT_MATCHED
    }

    private companion object {
        // 旧バージョンで使われていたpepper
        // "MineAuth": psw4j.propertiesに記載していた固定値
        // "": psw4j.propertiesが読み込まれずpepperなしで生成された場合
        val LEGACY_PEPPERS = listOf("MineAuth", "")
    }
}
