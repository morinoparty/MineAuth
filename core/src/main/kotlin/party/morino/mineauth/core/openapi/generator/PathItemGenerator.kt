package party.morino.mineauth.core.openapi.generator

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.openapi.model.MediaType
import party.morino.mineauth.core.openapi.model.Operation
import party.morino.mineauth.core.openapi.model.Parameter
import party.morino.mineauth.core.openapi.model.PathItem
import party.morino.mineauth.core.openapi.model.RequestBody
import party.morino.mineauth.core.openapi.model.Response
import party.morino.mineauth.core.openapi.model.Schema
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
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
                // TargetPlayerはアノテーションで指定されたパスセグメントとしてOpenAPIに公開する
                is ParameterInfo.TargetPlayer -> Parameter(
                    name = param.segment,
                    location = "path",
                    required = true,
                    schema = Schema(type = "string", description = "Player identifier: 'me', UUID, or player name")
                )
                // 型付きクエリパラメータはqueryパラメータとして公開する
                is ParameterInfo.QueryParam -> Parameter(
                    name = param.name,
                    location = "query",
                    required = !param.optional,
                    schema = schemaGenerator.generateSchema(param.type)
                )
                // QueryMapはMapなので個別パラメータとしては定義しない
                is ParameterInfo.QueryMap -> null
                // その他のパラメータタイプ（Body, Caller）はここでは扱わない
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
        val security = if (endpoint.access is EndpointAccess.Authenticated) {
            listOf(mapOf("oauth2" to listOf("openid", "plugin")))
        } else {
            null
        }

        // レスポンスを生成
        val responses = generateResponses(endpoint)

        return Operation(
            summary = generateSummary(endpoint),
            // @Public(reason)が指定されている場合はドキュメントに出力する
            description = (endpoint.access as? EndpointAccess.Public)?.reason?.let { "Public endpoint: $it" },
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

        // 成功レスポンス（Either<HttpError, T>の場合は登録時に解決済みのTを使用する）
        val returnSchema = schemaGenerator.generateResponseSchema(endpoint.responseType)
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
        val access = endpoint.access
        if (access is EndpointAccess.Authenticated) {
            responses["401"] = Response(description = "Unauthorized")

            // パーミッションまたはトークン種別の制限がある場合
            responses["403"] = Response(description = "Forbidden")
        }

        responses["500"] = Response(description = "Internal Server Error")

        return responses
    }
}
