package party.morino.mineauth.core.web.router.common.server

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.api.model.common.PluginDependenciesData
import party.morino.mineauth.api.model.common.PluginFileData
import party.morino.mineauth.api.model.common.PluginFileHashData
import party.morino.mineauth.api.model.common.PluginInfoData
import java.io.File
import java.security.MessageDigest

/**
 * Bukkitプラグインマネージャーから情報を取得するPluginInfoServiceの実装
 */
class PluginInfoServiceImpl : PluginInfoService {

    override fun getInstalledPlugins(): List<PluginInfoData> {
        return Bukkit.getPluginManager().plugins.map { plugin ->
            val meta = plugin.pluginMeta

            // JARファイルの取得（JavaPluginの場合のみ）
            val jarFile = resolveJarFile(plugin)

            PluginInfoData(
                name = meta.name,
                version = meta.version,
                description = meta.description,
                authors = meta.authors,
                website = meta.website,
                dependencies = PluginDependenciesData(
                    required = meta.pluginDependencies,
                    soft = meta.pluginSoftDependencies,
                ),
                // ファイル読み込み失敗時はnullにして他のプラグイン情報に影響させない
                file = jarFile?.let { runCatching { buildFileData(it) }.getOrNull() },
            )
        }
    }

    /**
     * JARファイルの情報を構築する
     * SHA-1とSHA-256を1回のストリームで同時計算する
     */
    private fun buildFileData(file: File): PluginFileData {
        val hashes = computeHashes(file)
        return PluginFileData(
            name = file.name,
            hash = PluginFileHashData(
                sha1 = hashes.first,
                sha256 = hashes.second,
            ),
        )
    }

    /**
     * プラグインのJARファイルを取得する
     * JavaPlugin.getFile()はprotectedなのでリフレクションを使用
     */
    private fun resolveJarFile(plugin: org.bukkit.plugin.Plugin): File? {
        if (plugin !is JavaPlugin) return null
        return runCatching {
            val method = JavaPlugin::class.java.getDeclaredMethod("getFile")
            method.isAccessible = true
            method.invoke(plugin) as File
        }.getOrNull()
    }

    /**
     * SHA-1とSHA-256を1回のファイル読み込みで同時に計算する
     *
     * @return Pair(sha1, sha256)
     */
    private fun computeHashes(file: File): Pair<String, String> {
        val sha1Digest = MessageDigest.getInstance("SHA-1")
        val sha256Digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                sha1Digest.update(buffer, 0, bytesRead)
                sha256Digest.update(buffer, 0, bytesRead)
            }
        }
        return Pair(
            sha1Digest.digest().joinToString("") { "%02x".format(it) },
            sha256Digest.digest().joinToString("") { "%02x".format(it) },
        )
    }
}
