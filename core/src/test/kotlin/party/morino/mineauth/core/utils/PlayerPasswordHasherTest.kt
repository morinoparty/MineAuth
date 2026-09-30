package party.morino.mineauth.core.utils

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.data.PasswordConfig
import kotlin.test.assertEquals

/**
 * PlayerPasswordHasherのハッシュ化と旧pepperへのフォールバックのテスト
 */
class PlayerPasswordHasherTest {
    private val config = PasswordConfig(pepper = "test-pepper")
    private val hasher = PlayerPasswordHasher(config)

    // 旧バージョンのpsw4j.propertiesと同じパラメータで生成したハッシュ
    private fun legacyHash(password: String, pepper: String): String =
        Password.hash(password)
            .addRandomSalt(64)
            .addPepper(pepper)
            .with(Argon2Function.getInstance(1024, 2, 1, 32, Argon2.ID, 19))
            .result

    @Test
    @DisplayName("Matches a hash created with the current pepper")
    fun matchesCurrentPepper() {
        assertEquals(PasswordVerification.MATCHED, hasher.verify("password", hasher.hash("password")))
    }

    @Test
    @DisplayName("Rejects a wrong password")
    fun rejectsWrongPassword() {
        assertEquals(PasswordVerification.NOT_MATCHED, hasher.verify("wrong", hasher.hash("password")))
    }

    @Test
    @DisplayName("Requires rehash for legacy pepper hashes")
    fun matchesLegacyHashes() {
        assertEquals(PasswordVerification.MATCHED_NEEDS_REHASH, hasher.verify("password", legacyHash("password", "MineAuth")))
        assertEquals(PasswordVerification.MATCHED_NEEDS_REHASH, hasher.verify("password", legacyHash("password", "")))
    }

    @Test
    @DisplayName("Requires rehash when Argon2 parameters changed")
    fun requiresRehashOnParameterChange() {
        val oldHash = PlayerPasswordHasher(config.copy(memory = 1024)).hash("password")
        assertEquals(PasswordVerification.MATCHED_NEEDS_REHASH, hasher.verify("password", oldHash))
    }
}
