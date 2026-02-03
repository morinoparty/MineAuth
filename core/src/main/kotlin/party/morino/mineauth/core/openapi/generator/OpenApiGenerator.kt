package party.morino.mineauth.core.openapi.generator

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.openapi.model.Components
import party.morino.mineauth.core.openapi.model.Info
import party.morino.mineauth.core.openapi.model.License
import party.morino.mineauth.core.openapi.model.OAuthFlow
import party.morino.mineauth.core.openapi.model.OAuthFlows
import party.morino.mineauth.core.openapi.model.OpenApiDocument
import party.morino.mineauth.core.openapi.model.PathItem
import party.morino.mineauth.core.openapi.model.SecurityScheme
import party.morino.mineauth.core.openapi.model.Tag
import party.morino.mineauth.core.openapi.registry.EndpointMetadataRegistry

/**
 * OpenAPIドキュメントを生成するジェネレーター
 * 動的に登録されたエンドポイントからOpenAPI 3.1.0仕様のドキュメントを構築する
 */
class OpenApiGenerator : KoinComponent {

    private val plugin: MineAuth by inject()
    private val metadataRegistry: EndpointMetadataRegistry by inject()
    private val pathItemGenerator: PathItemGenerator by inject()

    /**
     * 完全なOpenAPIドキュメントを生成する
     *
     * @return OpenAPI 3.1.0仕様のドキュメント
     */
    fun generate(): OpenApiDocument {
        // 動的エンドポイントからパスを生成
        val dynamicPaths = generateDynamicPaths()

        return OpenApiDocument(
            openapi = "3.1.0",
            info = generateInfo(),
            paths = dynamicPaths,
            components = generateComponents(),
            // グローバルsecurityは設定しない（各Operationで個別に設定）
            // グローバルで設定すると、認証不要なエンドポイントも認証必須に見えてしまう
            security = null,
            tags = generateTags()
        )
    }

    /**
     * API情報を生成する
     */
    private fun generateInfo(): Info {
        val pluginVersion = plugin.pluginMeta?.version ?: "1.0.0"

        return Info(
            title = "MineAuth API",
            description = """
                MineAuth OAuth2/OpenID Connect Authentication Server API.
                This API provides secure access to Minecraft player data through OAuth2 authentication.
            """.trimIndent(),
            version = pluginVersion,
            license = License(
                name = "CC0-1.0",
                url = "https://creativecommons.org/publicdomain/zero/1.0/"
            )
        )
    }

    /**
     * 動的に登録されたエンドポイントからパス定義を生成する
     */
    private fun generateDynamicPaths(): Map<String, PathItem> {
        val paths = mutableMapOf<String, PathItem>()

        for ((pluginName, registeredEndpoints) in metadataRegistry.getAllEndpoints()) {
            for (endpoint in registeredEndpoints.endpoints) {
                // 完全なパスを構築（スラッシュの重複を防ぐ）
                val fullPath = normalizePath(registeredEndpoints.basePath, endpoint.path)

                // オペレーションを生成
                val operation = pathItemGenerator.generateOperation(
                    registeredEndpoints.basePath,
                    endpoint
                )

                // パスが既に存在する場合はマージ（同一パス・同一HTTPメソッドは上書き）
                val existingPathItem = paths[fullPath]
                if (existingPathItem != null) {
                    plugin.logger.warning(
                        "Path $fullPath already exists, merging operations for plugin: $pluginName"
                    )
                }
                paths[fullPath] = pathItemGenerator.mergeOperation(
                    existingPathItem,
                    endpoint.httpMethod,
                    operation
                )
            }
        }

        return paths
    }

    /**
     * ベースパスとエンドポイントパスを結合して正規化する
     * 重複するスラッシュを除去し、先頭にスラッシュを保証する
     */
    private fun normalizePath(basePath: String, endpointPath: String): String {
        val base = basePath.trimEnd('/')
        val endpoint = endpointPath.trimStart('/')
        return if (endpoint.isEmpty()) base else "$base/$endpoint"
    }

    /**
     * コンポーネント定義を生成する
     * OAuth2セキュリティスキームを含む
     */
    private fun generateComponents(): Components {
        return Components(
            securitySchemes = mapOf(
                "oauth2" to SecurityScheme(
                    type = "oauth2",
                    description = "OAuth2 Authorization Code Flow",
                    flows = OAuthFlows(
                        authorizationCode = OAuthFlow(
                            authorizationUrl = "/oauth2/authorize",
                            tokenUrl = "/oauth2/token",
                            scopes = mapOf(
                                "read.*" to "Grants read access for resources",
                                "write.*" to "Grants write access for resources"
                            )
                        )
                    )
                )
            )
        )
    }

    /**
     * タグ一覧を生成する
     * 各プラグインに対応するタグを動的に生成
     * PathItemGeneratorと同じロジックでタグ名を生成し一貫性を保つ
     */
    private fun generateTags(): List<Tag> {
        val tags = mutableListOf<Tag>()

        // 動的タグ（プラグインごと）
        for ((pluginName, registeredEndpoints) in metadataRegistry.getAllEndpoints()) {
            // basePathからタグ名を抽出（PathItemGeneratorと同じロジック）
            val tagName = extractTagFromBasePath(registeredEndpoints.basePath)
            tags.add(
                Tag(
                    name = tagName,
                    description = "$pluginName API endpoints"
                )
            )
        }

        return tags.takeIf { it.isNotEmpty() } ?: emptyList()
    }

    /**
     * ベースパスからタグ名を抽出する
     * PathItemGenerator.extractPluginTagと同じロジック
     * 例: /api/v1/plugins/vaultaddon -> vaultaddon
     */
    private fun extractTagFromBasePath(basePath: String): String {
        return basePath.split("/").lastOrNull { it.isNotEmpty() } ?: "unknown"
    }
}
