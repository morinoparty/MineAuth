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
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
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

        // 宣言されたメンバー関数のみをイテレート（継承メソッドは除外）
        for (method in handlerClass.declaredMemberFunctions) {
            // HTTPマッピングアノテーションを持つメソッドのみ処理
            val httpMapping = extractHttpMapping(method) ?: continue

            // パラメータを解析
            val parameters = analyzeParameters(method).bind()

            // パーミッションを取得（メソッドレベルが優先）
            val permission = method.findAnnotation<Permission>()?.value ?: classPermission

            // 認証が必要かどうか判定（@Permissionがあれば必ず認証必須）
            val requiresAuth = requiresAuthentication(method, permission != null)

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
     * 以下のいずれかの条件で認証必須:
     * - @AuthedAccessUserまたは@TargetPlayerアノテーションが付いたパラメータがある
     * - @Permissionアノテーションがメソッドまたはクラスに付いている
     *
     * セキュリティ: @Permissionが付いている場合は必ず認証を要求する
     * これにより、権限チェックをバイパスする攻撃を防止する
     *
     * @param method 対象のメソッド
     * @param hasPermission メソッドまたはクラスに@Permissionが設定されているか
     * @return 認証が必要な場合true
     */
    private fun requiresAuthentication(method: KFunction<*>, hasPermission: Boolean): Boolean {
        // @Permissionが付いていれば必ず認証必須
        if (hasPermission) {
            return true
        }

        // @AuthedAccessUserまたは@TargetPlayerがあれば認証必須
        return method.parameters.any { param ->
            param.hasAnnotation<AuthedAccessUser>() || param.hasAnnotation<TargetPlayer>()
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
            param.hasAnnotation<PathParam>(),
            param.hasAnnotation<QueryParams>(),
            param.hasAnnotation<RequestBody>(),
            param.hasAnnotation<AuthedAccessUser>(),
            param.hasAnnotation<AccessUser>(),
            param.hasAnnotation<TargetPlayer>()
        ).count { it }

        // 複数のアノテーションが付与されている場合はエラー
        ensure(annotationCount <= 1) {
            AnnotationProcessError.ConflictingAnnotations(paramName)
        }

        // 各アノテーションに対応したParameterInfoを返す
        when {
            param.hasAnnotation<PathParam>() -> {
                // パスパラメータ
                val paramAnnotation = param.findAnnotation<PathParam>()!!
                ParameterInfo.PathParam(paramAnnotation.value, paramType)
            }
            param.hasAnnotation<QueryParams>() -> {
                ParameterInfo.QueryParams(paramType)
            }
            param.hasAnnotation<RequestBody>() -> {
                ParameterInfo.Body(paramType)
            }
            param.hasAnnotation<AuthedAccessUser>() -> {
                ParameterInfo.AuthenticatedPlayer(paramType)
            }
            param.hasAnnotation<AccessUser>() -> {
                ParameterInfo.AccessPlayer(paramType)
            }
            param.hasAnnotation<TargetPlayer>() -> {
                // パスパラメータ {player} で指定されたプレイヤーを解決
                ParameterInfo.TargetPlayer(paramType)
            }
            else -> {
                // アノテーションがないパラメータはエラー
                raise(AnnotationProcessError.MissingAnnotation(paramName))
            }
        }
    }
}
