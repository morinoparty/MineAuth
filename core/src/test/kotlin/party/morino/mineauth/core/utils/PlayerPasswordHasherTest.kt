package party.morino.mineauth.core.utils

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.data.PasswordConfig
import kotlin.test.assertEquals

/**
 * PlayerPasswordHasherのハッシュ化と再ハッシュ判定のテスト
 */
class PlayerPasswordHasherTest {
    private val config = PasswordConfig(pepper = "test-pepper")
    private val hasher = PlayerPasswordHasher(config)

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
    @DisplayName("Rejects a hash created with a different pepper")
    fun rejectsDifferentPepper() {
        val otherHash = PlayerPasswordHasher(config.copy(pepper = "MineAuth")).hash("password")
        assertEquals(PasswordVerification.NOT_MATCHED, hasher.verify("password", otherHash))
    }

    @Test
    @DisplayName("Requires rehash when Argon2 parameters changed")
    fun requiresRehashOnParameterChange() {
        val oldHash = PlayerPasswordHasher(config.copy(memory = 1024)).hash("password")
        assertEquals(PasswordVerification.MATCHED_NEEDS_REHASH, hasher.verify("password", oldHash))
    }
}
