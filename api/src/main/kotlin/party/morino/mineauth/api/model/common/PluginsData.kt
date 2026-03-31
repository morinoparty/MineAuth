package party.morino.mineauth.api.model.common

import kotlinx.serialization.Serializable

/**
 * インストール済みプラグインの詳細情報
 */
@Serializable
data class PluginInfoData(
    // プラグイン名
    val name: String,
    // プラグインのバージョン
    val version: String,
    // プラグインの説明
    val description: String? = null,
    // プラグインの作者リスト
    val authors: List<String> = emptyList(),
    // プラグインのWebサイト
    val website: String? = null,
    // プラグインの依存関係
    val dependencies: PluginDependenciesData = PluginDependenciesData(),
    // JARファイル情報
    val file: PluginFileData? = null,
)

/**
 * プラグインの依存関係情報
 */
@Serializable
data class PluginDependenciesData(
    // 必須依存プラグイン名のリスト
    val required: List<String> = emptyList(),
    // ソフト依存（任意）プラグイン名のリスト
    val soft: List<String> = emptyList(),
)

/**
 * プラグインのJARファイル情報
 */
@Serializable
data class PluginFileData(
    // JARファイル名
    val name: String,
    // ファイルハッシュ
    val hash: PluginFileHashData,
)

/**
 * ファイルハッシュ情報
 */
@Serializable
data class PluginFileHashData(
    // SHA-1ハッシュ
    val sha1: String,
    // SHA-256ハッシュ
    val sha256: String,
)
