package party.morino.mineauth.core.openapi.generator

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.openapi.model.MediaType
import party.morino.mineauth.core.openapi.model.Operation
import party.morino.mineauth.core.openapi.model.Parameter
import party.morino.mineauth.core.openapi.model.PathItem
import party.morino.mineauth.core.openapi.model.RequestBody
import party.morino.mineauth.core.openapi.model.Response
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.ParameterInfo

/**
 * EndpointMetadataからOpenAPIのPathItem/Operationを生成するジェネレーター
 */
class PathItemGenerator : KoinComponent {

    private val schemaGenerator: SchemaGenerator by inject()

    /**
     * EndpointMetadataからOperationを生成する
     *
     * @param basePath プラグインのベースパス
     * @param endpoint エンドポイントメタデータ
     * @return 生成されたOperation
     */
    fun generateOperation(basePath: String, endpoint: EndpointMetadata): Operation {
        // パラメータを生成
        val parameters = endpoint.parameters.mapNotNull { param ->
            when (param) {
                is ParameterInfo.PathParam -> Parameter(
                    name = param.name,
                    location = "path",
                    required = true,
                    schema = schemaGenerator.generateSchema(param.type)
                )
                // QueryParamsはMapなので個別パラメータとしては定義しない
                is ParameterInfo.QueryParams -> null
                // その他のパラメータタイプは無視
                else -> null
            }
        }

        // リクエストボディを生成
        val requestBody = endpoint.parameters
            .filterIsInstance<ParameterInfo.Body>()
            .firstOrNull()
            ?.let { body ->
                RequestBody(
                    content = mapOf(
                        "application/json" to MediaType(
                            schema = schemaGenerator.generateSchema(body.type)
                        )
                    ),
                    // nullableな型の場合はrequired = false
                    required = !body.type.isMarkedNullable
                )
            }

        // セキュリティ要件を生成
        val security = if (endpoint.requiresAuthentication) {
            listOf(mapOf("oauth2" to listOf("read.*", "write.*")))
        } else {
            null
        }

        // レスポンスを生成
        val responses = generateResponses(endpoint)

        return Operation(
            summary = generateSummary(endpoint),
            operationId = generateOperationId(basePath, endpoint),
            tags = listOf(extractPluginTag(basePath)),
            parameters = parameters.takeIf { it.isNotEmpty() },
            requestBody = requestBody,
            responses = responses,
            security = security
        )
    }

    /**
     * HTTPメソッドに応じてPathItemを更新する
     *
     * @param pathItem 既存のPathItem（なければ新規作成）
     * @param httpMethod HTTPメソッド
     * @param operation 追加するオペレーション
     * @return 更新されたPathItem
     */
    fun mergeOperation(
        pathItem: PathItem?,
        httpMethod: HttpMethodType,
        operation: Operation
    ): PathItem {
        val existing = pathItem ?: PathItem()
        return when (httpMethod) {
            HttpMethodType.GET -> existing.copy(get = operation)
            HttpMethodType.POST -> existing.copy(post = operation)
            HttpMethodType.PUT -> existing.copy(put = operation)
            HttpMethodType.DELETE -> existing.copy(delete = operation)
            HttpMethodType.PATCH -> existing.copy(patch = operation)
        }
    }

    /**
     * エンドポイントのサマリーを生成する
     * メソッド名からキャメルケースを分解して読みやすい形式に変換
     */
    private fun generateSummary(endpoint: EndpointMetadata): String {
        val methodName = endpoint.method.name
        // キャメルケースをスペース区切りに変換
        return methodName
            .replace(Regex("([A-Z])")) { " ${it.value}" }
            .trim()
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * オペレーションIDを生成する
     * 形式: {httpMethod}_{pluginName}_{methodName}
     */
    private fun generateOperationId(basePath: String, endpoint: EndpointMetadata): String {
        val pluginName = extractPluginTag(basePath)
        val methodName = endpoint.method.name
        return "${endpoint.httpMethod.name.lowercase()}_${pluginName}_$methodName"
    }

    /**
     * ベースパスからプラグイン名（タグ名）を抽出する
     * 例: /api/v1/plugins/vaultaddon -> vaultaddon
     */
    private fun extractPluginTag(basePath: String): String {
        return basePath.split("/").lastOrNull { it.isNotEmpty() } ?: "unknown"
    }

    /**
     * レスポンス定義を生成する
     */
    private fun generateResponses(endpoint: EndpointMetadata): Map<String, Response> {
        val responses = mutableMapOf<String, Response>()

        // 成功レスポンス
        val returnSchema = schemaGenerator.generateResponseSchema(endpoint.method.returnType)
        responses["200"] = if (returnSchema != null) {
            Response(
                description = "Successful response",
                content = mapOf(
                    "application/json" to MediaType(schema = returnSchema)
                )
            )
        } else {
            Response(description = "Successful response")
        }

        // エラーレスポンス
        responses["400"] = Response(description = "Bad Request")

        // 認証が必要な場合
        if (endpoint.requiresAuthentication) {
            responses["401"] = Response(description = "Unauthorized")
        }

        // パーミッションが必要な場合
        if (endpoint.requiredPermission != null) {
            responses["403"] = Response(description = "Forbidden")
        }

        responses["500"] = Response(description = "Internal Server Error")

        return responses
    }
}
