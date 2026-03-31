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
                file = jarFile?.let { buildFileData(it) },
            )
        }
    }

    /**
     * JARファイルの情報を構築する
     */
    private fun buildFileData(file: File): PluginFileData {
        return PluginFileData(
            name = file.name,
            hash = PluginFileHashData(
                sha1 = computeHash(file, "SHA-1"),
                sha256 = computeHash(file, "SHA-256"),
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
     * ファイルのハッシュを計算する
     */
    private fun computeHash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
