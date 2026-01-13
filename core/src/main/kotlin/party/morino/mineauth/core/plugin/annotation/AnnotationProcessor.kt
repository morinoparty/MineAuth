package party.morino.mineauth.core.plugin.annotation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.annotations.*
import party.morino.mineauth.api.http.HttpMethod
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.jvmErasure

/**
 * ハンドラークラスのアノテーションを解析するプロセッサ
 * Kotlinリフレクションを使用してメソッドとパラメータのアノテーションを読み取る
 */
class AnnotationProcessor : KoinComponent {

    /**
     * ハンドラーインスタンスを解析し、エンドポイントメタデータのリストを返す
     *
     * @param handlerInstance アノテーションが付与されたハンドラークラスのインスタンス
     * @return 解析結果のエンドポイントメタデータリスト
     */
    fun process(handlerInstance: Any): Either<AnnotationProcessError, List<EndpointMetadata>> = either {
        val handlerClass = handlerInstance::class
        val endpoints = mutableListOf<EndpointMetadata>()

        // クラスレベルの@Permissionアノテーションを取得
        val classPermission = handlerClass.findAnnotation<Permission>()?.value

        // 全メソッドをイテレート
        for (method in handlerClass.functions) {
            // HTTPマッピングアノテーションを持つメソッドのみ処理
            val httpMapping = extractHttpMapping(method) ?: continue

            // パラメータを解析
            val parameters = analyzeParameters(method).bind()

            // 認証が必要かどうか判定
            val requiresAuth = requiresAuthentication(method)

            // パーミッションを取得（メソッドレベルが優先）
            val permission = method.findAnnotation<Permission>()?.value ?: classPermission

            // EndpointMetadataを構築
            endpoints.add(
                EndpointMetadata(
                    method = method,
                    handlerInstance = handlerInstance,
                    path = httpMapping.second,
                    httpMethod = httpMapping.first,
                    requiresAuthentication = requiresAuth,
                    requiredPermission = permission,
                    parameters = parameters,
                    isSuspending = method.isSuspend
                )
            )
        }

        endpoints
    }

    /**
     * メソッドからHTTPメソッドとパスを抽出する
     *
     * @param method 対象のメソッド
     * @return HTTPメソッドとパスのペア、アノテーションがない場合はnull
     */
    private fun extractHttpMapping(method: KFunction<*>): Pair<HttpMethodType, String>? {
        // @GetMappingをチェック
        method.findAnnotation<GetMapping>()?.let {
            return HttpMethodType.GET to it.value
        }

        // @PostMappingをチェック
        method.findAnnotation<PostMapping>()?.let {
            return HttpMethodType.POST to it.value
        }

        // @PutMappingをチェック
        method.findAnnotation<PutMapping>()?.let {
            return HttpMethodType.PUT to it.value
        }

        // @DeleteMappingをチェック
        method.findAnnotation<DeleteMapping>()?.let {
            return HttpMethodType.DELETE to it.value
        }

        // @HttpHandlerをチェック
        method.findAnnotation<HttpHandler>()?.let {
            val methodType = when (it.method) {
                HttpMethod.GET -> HttpMethodType.GET
                HttpMethod.POST -> HttpMethodType.POST
                HttpMethod.PUT -> HttpMethodType.PUT
                HttpMethod.DELETE -> HttpMethodType.DELETE
                HttpMethod.PATCH -> HttpMethodType.PATCH
            }
            return methodType to it.path
        }

        return null
    }

    /**
     * メソッドが認証を必要とするか判定する
     * @AuthedAccessUserまたは@Authenticatedアノテーションが付いたパラメータがあれば認証必須
     *
     * @param method 対象のメソッド
     * @return 認証が必要な場合true
     */
    private fun requiresAuthentication(method: KFunction<*>): Boolean {
        return method.parameters.any { param ->
            param.hasAnnotation<AuthedAccessUser>() || param.hasAnnotation<Authenticated>()
        }
    }

    /**
     * メソッドのパラメータを解析してParameterInfoリストを生成する
     *
     * @param method 対象のメソッド
     * @return パラメータ情報のリスト
     */
    private fun analyzeParameters(method: KFunction<*>): Either<AnnotationProcessError, List<ParameterInfo>> = either {
        val parameterInfoList = mutableListOf<ParameterInfo>()

        for (param in method.parameters) {
            // インスタンスパラメータ（this）はスキップ
            if (param.kind == KParameter.Kind.INSTANCE) continue

            val paramInfo = analyzeParameter(param).bind()
            parameterInfoList.add(paramInfo)
        }

        parameterInfoList
    }

    /**
     * 単一のパラメータを解析する
     *
     * @param param 対象のパラメータ
     * @return パラメータ情報
     */
    private fun analyzeParameter(param: KParameter): Either<AnnotationProcessError, ParameterInfo> = either {
        val paramName = param.name ?: "unknown"
        val paramType = param.type

        // アノテーションの数をカウント（複数付与されていないか確認）
        val annotationCount = listOf(
            param.hasAnnotation<Params>(),
            param.hasAnnotation<RequestParams>(),
            param.hasAnnotation<RequestBody>(),
            param.hasAnnotation<AuthedAccessUser>(),
            param.hasAnnotation<AccessUser>(),
            param.hasAnnotation<Authenticated>()
        ).count { it }

        // 複数のアノテーションが付与されている場合はエラー
        ensure(annotationCount <= 1) {
            AnnotationProcessError.ConflictingAnnotations(paramName)
        }

        // 各アノテーションに対応したParameterInfoを返す
        when {
            param.hasAnnotation<Params>() -> {
                val paramsAnnotation = param.findAnnotation<Params>()!!
                ParameterInfo.PathParam(paramsAnnotation.value.toList(), paramType)
            }
            param.hasAnnotation<RequestParams>() -> {
                ParameterInfo.QueryParams(paramType)
            }
            param.hasAnnotation<RequestBody>() -> {
                ParameterInfo.Body(paramType)
            }
            param.hasAnnotation<AuthedAccessUser>() || param.hasAnnotation<Authenticated>() -> {
                ParameterInfo.AuthenticatedPlayer(paramType)
            }
            param.hasAnnotation<AccessUser>() -> {
                ParameterInfo.AccessPlayer(paramType)
            }
            else -> {
                // アノテーションがないパラメータはエラー
                raise(AnnotationProcessError.MissingAnnotation(paramName))
            }
        }
    }
}
