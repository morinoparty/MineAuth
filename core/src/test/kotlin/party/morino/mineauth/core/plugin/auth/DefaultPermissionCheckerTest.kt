package party.morino.mineauth.core.plugin.auth

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import net.luckperms.api.util.Tristate
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import java.util.UUID
import kotlin.test.assertEquals

/**
 * DefaultPermissionCheckerのユニットテスト
 * オンライン（Paper標準API）とオフライン（LuckPermsフォールバック）の分岐を検証する
 *
 * Bukkitのstatic APIはmockkStaticで差し替えるため、サーバー実装は不要
 */
class DefaultPermissionCheckerTest {

    private val checker = DefaultPermissionChecker()

    private val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
    private val node = "mineauth.test.permission"

    private lateinit var pluginManager: PluginManager
    private lateinit var offlinePlayer: OfflinePlayer

    @BeforeEach
    fun setUp() {
        mockkStatic(Bukkit::class)
        mockkObject(LuckPermsIntegration)

        pluginManager = mockk(relaxed = true)
        offlinePlayer = mockk(relaxed = true)
        every { Bukkit.getPluginManager() } returns pluginManager
        every { Bukkit.getOfflinePlayer(uuid) } returns offlinePlayer
        // 既定ではオフライン・非OP・LuckPerms未導入とする
        every { Bukkit.getPlayer(uuid) } returns null
        every { offlinePlayer.isOp } returns false
        every { pluginManager.getPermission(node) } returns null
        coEvery { LuckPermsIntegration.checkPermission(any(), any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("Online player is evaluated by Paper API")
    fun onlinePlayerUsesPaperApi() = runTest {
        val player = mockk<Player>()
        every { Bukkit.getPlayer(uuid) } returns player
        every { player.hasPermission(node) } returns true

        assertEquals(PermissionCheckResult.GRANTED, checker.check(uuid, node))
    }

    @Test
    @DisplayName("Offline player without LuckPerms is unresolvable")
    fun offlineWithoutLuckPermsIsUnresolvable() = runTest {
        // LuckPerms未導入時はnullが返り、評価不能として扱われる（黙って許可しない）
        assertEquals(PermissionCheckResult.UNRESOLVABLE, checker.check(uuid, node))
    }

    @Test
    @DisplayName("Offline player is granted by LuckPerms")
    fun offlineGrantedByLuckPerms() = runTest {
        coEvery { LuckPermsIntegration.checkPermission(uuid, node) } returns Tristate.TRUE

        assertEquals(PermissionCheckResult.GRANTED, checker.check(uuid, node))
    }

    @Test
    @DisplayName("Offline player is denied by LuckPerms")
    fun offlineDeniedByLuckPerms() = runTest {
        coEvery { LuckPermsIntegration.checkPermission(uuid, node) } returns Tristate.FALSE

        assertEquals(PermissionCheckResult.DENIED, checker.check(uuid, node))
    }

    @Test
    @DisplayName("Undefined permission falls back to Bukkit default")
    fun undefinedFallsBackToBukkitDefault() = runTest {
        // LuckPermsで未設定のノードは、オンライン時と同様に登録済みのdefaultで解決される
        coEvery { LuckPermsIntegration.checkPermission(uuid, node) } returns Tristate.UNDEFINED
        every { pluginManager.getPermission(node) } returns Permission(node, PermissionDefault.TRUE)

        assertEquals(PermissionCheckResult.GRANTED, checker.check(uuid, node))
    }

    @Test
    @DisplayName("Undefined permission on unregistered node denies non-op")
    fun undefinedUnregisteredNodeDeniesNonOp() = runTest {
        // 未登録ノードのBukkit既定はOP限定のため、非OPは拒否される
        coEvery { LuckPermsIntegration.checkPermission(uuid, node) } returns Tristate.UNDEFINED

        assertEquals(PermissionCheckResult.DENIED, checker.check(uuid, node))
    }
}
