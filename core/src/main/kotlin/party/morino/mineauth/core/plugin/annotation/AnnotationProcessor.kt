package party.morino.mineauth.core.plugin.annotation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.RegistrationError
import party.morino.mineauth.api.annotations.Authenticated
import party.morino.mineauth.api.annotations.Body
import party.morino.mineauth.api.annotations.Caller
import party.morino.mineauth.api.annotations.Delete
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.Patch
import party.morino.mineauth.api.annotations.Path
import party.morino.mineauth.api.annotations.PlayerParam
import party.morino.mineauth.api.annotations.Post
import party.morino.mineauth.api.annotations.Public
import party.morino.mineauth.api.annotations.Put
import party.morino.mineauth.api.annotations.Query
import party.morino.mineauth.api.annotations.QueryMap
import party.morino.mineauth.api.annotations.Route
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.api.http.HttpError
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf

/**
 * ハンドラークラスのアノテーションを解析するプロセッサ
 * Kotlinリフレクションを使用してメソッドとパラメータのアノテーションを読み取る
 *
 * 全ての検証エラーを累積して返す（fail-fastしない）ことで、
 * プラグイン開発者が1回の登録試行で全ての問題を把握できるようにする
 */
class AnnotationProcessor : KoinComponent {

    companion object {
        // @Path/@Queryで自動変換可能な型の一覧
        private val CONVERTIBLE_TYPES: Set<KClass<*>> = setOf(
            String::class, Int::class, Long::class,
            Double::class, Float::class, Boolean::class, UUID::class
        )
        private val CONVERTIBLE_TYPE_NAMES = CONVERTIBLE_TYPES.map { it.simpleName ?: "?" }
    }

    /**
     * ハンドラーインスタンスを解析し、エンドポイントメタデータのリストを返す
     * 検証エラーがある場合は全エラーを累積したNonEmptyListを返す
     *
     * @param handlerInstance アノテーションが付与されたハンドラークラスのインスタンス
     * @return 全検証エラーのリスト、または解析済みエンドポイントメタデータのリスト
     */
    fun process(handlerInstance: Any): Either<NonEmptyList<RegistrationError>, List<EndpointMetadata>> {
        val handlerClass = handlerInstance::class
        val className = handlerClass.qualifiedName ?: handlerClass.toString()
        val errors = mutableListOf<RegistrationError>()
        val endpoints = mutableListOf<EndpointMetadata>()

        // クラスレベルの@Routeプレフィックスを取得
        val routePrefix = handlerClass.findAnnotation<Route>()?.value ?: ""

        // 宣言されたメンバー関数のみをイテレート（継承メソッドは除外）
        for (function in handlerClass.declaredMemberFunctions) {
            // HTTPメソッドアノテーションを持つメソッドのみ処理
            val mappings = extractHttpMappings(function)
            if (mappings.isEmpty()) continue

            // このメソッドで新たに発生したエラーを追跡し、エラーがなければエンドポイントを追加する
            val errorCountBefore = errors.size

            if (mappings.size > 1) {
                errors += RegistrationError.ConflictingHttpMethods(className, function.name)
                continue
            }
            val (httpMethod, rawPath) = mappings.first()

            // リフレクション実行の前提として、エンドポイントメソッドはpublicである必要がある
            if (function.visibility != KVisibility.PUBLIC) {
                errors += RegistrationError.InvalidAccessConfiguration(
                    className, function.name,
                    "endpoint methods must be public (found ${function.visibility})"
                )
                continue
            }

            // @Routeプレフィックスを含めてパスを正規化
            val path = normalizePath(routePrefix, rawPath)
            val segments = parseSegments(path)

            // アクセス宣言（@Public xor @Authenticated）を解析
            val access = extractAccess(className, function, errors)

            // パラメータを解析（アクセス宣言が不正でもエラー累積のため続行する）
            val parameters = function.parameters
                .filter { it.kind == KParameter.Kind.VALUE }
                .mapNotNull { param ->
                    analyzeParameter(className, function, param, access, segments, path, errors)
                }

            // リクエストボディは1エンドポイントにつき最大1つ
            if (parameters.count { it is ParameterInfo.Body } > 1) {
                errors += RegistrationError.MultipleBodyParameters(className, function.name)
            }

            // 戻り値型を解析（Either<HttpError, T>のアンラップ）
            val (responseType, returnsEither) = analyzeReturnType(className, function, errors)

            // このメソッドでエラーが発生していなければエンドポイントとして追加
            if (errors.size == errorCountBefore && access != null) {
                endpoints.add(
                    EndpointMetadata(
                        method = function,
                        handlerInstance = handlerInstance,
                        path = path,
                        pathSegments = segments,
                        httpMethod = httpMethod,
                        access = access,
                        parameters = parameters,
                        isSuspending = function.isSuspend,
                        responseType = responseType,
                        returnsEither = returnsEither
                    )
                )
            }
        }

        // エンドポイントが1つもないハンドラーは登録ミスの可能性が高いためエラー
        if (endpoints.isEmpty() && errors.isEmpty()) {
            errors += RegistrationError.NoEndpoints(className)
        }

        return errors.toNonEmptyListOrNull()?.left() ?: endpoints.toList().right()
    }

    /**
     * メソッドに付与されたHTTPメソッドアノテーションを全て抽出する
     * 複数付与の検出のためリストで返す
     *
     * @param function 対象のメソッド
     * @return HTTPメソッドとパスのペアのリスト
     */
    private fun extractHttpMappings(function: KFunction<*>): List<Pair<HttpMethodType, String>> =
        listOfNotNull(
            function.findAnnotation<Get>()?.let { HttpMethodType.GET to it.value },
            function.findAnnotation<Post>()?.let { HttpMethodType.POST to it.value },
            function.findAnnotation<Put>()?.let { HttpMethodType.PUT to it.value },
            function.findAnnotation<Delete>()?.let { HttpMethodType.DELETE to it.value },
            function.findAnnotation<Patch>()?.let { HttpMethodType.PATCH to it.value }
        )

    /**
     * アクセス宣言（@Public xor @Authenticated）を解析する
     * どちらも無い・両方あるはエラーとしてnullを返す
     *
     * @param className ハンドラークラス名（エラー用）
     * @param function 対象のメソッド
     * @param errors エラー累積リスト
     * @return 解析されたアクセス制御、エラー時はnull
     */
    private fun extractAccess(
        className: String,
        function: KFunction<*>,
        errors: MutableList<RegistrationError>
    ): EndpointAccess? {
        val public = function.findAnnotation<Public>()
        val authenticated = function.findAnnotation<Authenticated>()

        return when {
            public != null && authenticated != null -> {
                errors += RegistrationError.ConflictingAccessDeclaration(className, function.name)
                null
            }

            public != null -> EndpointAccess.Public(public.reason.ifEmpty { null })

            authenticated != null -> {
                val callers = authenticated.callers.toSet()
                if (callers.isEmpty()) {
                    errors += RegistrationError.InvalidAccessConfiguration(
                        className, function.name, "@Authenticated callers must not be empty"
                    )
                    null
                } else {
                    EndpointAccess.Authenticated(
                        permission = authenticated.permission.ifEmpty { null },
                        callers = callers
                    )
                }
            }

            else -> {
                errors += RegistrationError.MissingAccessDeclaration(className, function.name)
                null
            }
        }
    }

    /**
     * 単一のパラメータを解析する
     * エラーは累積リストに追加し、正常時のみParameterInfoを返す
     *
     * @param className ハンドラークラス名（エラー用）
     * @param function 対象のメソッド
     * @param param 対象のパラメータ
     * @param access エンドポイントのアクセス制御（不正時はnull）
     * @param segments パスセグメント（パスパラメータ検証用）
     * @param path 正規化済みパス（エラーメッセージ用）
     * @param errors エラー累積リスト
     * @return 解析されたパラメータ情報、エラー時はnull
     */
    private fun analyzeParameter(
        className: String,
        function: KFunction<*>,
        param: KParameter,
        access: EndpointAccess?,
        segments: List<PathSegment>,
        path: String,
        errors: MutableList<RegistrationError>
    ): ParameterInfo? {
        val paramName = param.name ?: "unknown"

        // パラメータアノテーションの付与数を確認（0個・複数個はエラー）
        val annotationCount = listOf(
            param.hasAnnotation<Path>(),
            param.hasAnnotation<Query>(),
            param.hasAnnotation<QueryMap>(),
            param.hasAnnotation<Body>(),
            param.hasAnnotation<Caller>(),
            param.hasAnnotation<PlayerParam>()
        ).count { it }

        if (annotationCount == 0) {
            errors += RegistrationError.MissingParameterAnnotation(className, function.name, paramName)
            return null
        }
        if (annotationCount > 1) {
            errors += RegistrationError.ConflictingParameterAnnotations(className, function.name, paramName)
            return null
        }

        return when {
            param.hasAnnotation<Path>() ->
                analyzePathParam(className, function, param, segments, path, errors)

            param.hasAnnotation<Query>() ->
                analyzeQueryParam(className, function, param, errors)

            param.hasAnnotation<QueryMap>() ->
                analyzeQueryMap(className, function, param, errors)

            param.hasAnnotation<Body>() ->
                analyzeBody(className, function, param, errors)

            param.hasAnnotation<Caller>() ->
                analyzeCaller(className, function, param, access, errors)

            else ->
                analyzePlayerParam(className, function, param, access, segments, path, errors)
        }
    }

    /**
     * @Pathパラメータを解析する
     */
    private fun analyzePathParam(
        className: String,
        function: KFunction<*>,
        param: KParameter,
        segments: List<PathSegment>,
        path: String,
        errors: MutableList<RegistrationError>
    ): ParameterInfo? {
        val paramName = param.name ?: "unknown"
        val annotation = param.findAnnotation<Path>()!!

        // 型が変換可能かチェック
        if (param.type.jvmErasure !in CONVERTIBLE_TYPES) {
            errors += RegistrationError.UnsupportedParameterType(
                className, function.name, paramName,
                param.type.toString(), CONVERTIBLE_TYPE_NAMES
            )
            return null
        }

        // パスに対応するセグメントが存在するかチェック
        if (segments.none { it is PathSegment.Param && it.name == annotation.value }) {
            errors += RegistrationError.PathParameterMismatch(
                className, function.name, paramName, annotation.value, path
            )
            return null
        }

        return ParameterInfo.PathParam(annotation.value, param.type)
    }

    /**
     * @Queryパラメータを解析する
     * nullableの場合は省略可能なパラメータとして扱う
     */
    private fun analyzeQueryParam(
        className: String,
        function: KFunction<*>,
        param: KParameter,
        errors: MutableList<RegistrationError>
    ): ParameterInfo? {
        val paramName = param.name ?: "unknown"
        val annotation = param.findAnnotation<Query>()!!

        if (param.type.jvmErasure !in CONVERTIBLE_TYPES) {
            errors += RegistrationError.UnsupportedParameterType(
                className, function.name, paramName,
                param.type.toString(), CONVERTIBLE_TYPE_NAMES
            )
            return null
        }

        return ParameterInfo.QueryParam(
            name = annotation.value,
            type = param.type,
            optional = param.type.isMarkedNullable
        )
    }

    /**
     * @QueryMapパラメータを解析する
     * Map<String, String>のみ許可する
     */
    private fun analyzeQueryMap(
        className: String,
        function: KFunction<*>,
        param: KParameter,
        errors: MutableList<RegistrationError>
    ): ParameterInfo? {
        val paramName = param.name ?: "unknown"

        // 実行時にはString値しか渡せないため、型引数までMap<String, String>であることを検証する
        val isStringToStringMap = param.type.jvmErasure == Map::class &&
            param.type.arguments.size == 2 &&
            param.type.arguments.all { argument ->
                argument.type?.jvmErasure == String::class && argument.type?.isMarkedNullable == false
            }
        if (!isStringToStringMap) {
            errors += RegistrationError.UnsupportedParameterType(
                className, function.name, paramName,
                param.type.toString(), listOf("Map<String, String>")
            )
            return null
        }

        return ParameterInfo.QueryMap(param.type)
    }

    /**
     * @Bodyパラメータを解析する
     * シリアライザを登録時に解決・検証することで、リクエスト時の失敗を防ぐ
     */
    private fun analyzeBody(
        className: String,
        function: KFunction<*>,
        param: KParameter,
        errors: MutableList<RegistrationError>
    ): ParameterInfo? {
        val paramName = param.name ?: "unknown"

        // シリアライザを登録時に解決する（@Serializableでない型はここで検出される）
        val bodySerializer = try {
            @Suppress("UNCHECKED_CAST")
            serializer(param.type) as KSerializer<Any?>
        } catch (e: Exception) {
            errors += RegistrationError.BodyNotSerializable(
                className, function.name, paramName, e.message ?: "serializer resolution failed"
            )
            return null
        }

        return ParameterInfo.Body(param.type, bodySerializer)
    }

    /**
     * @Callerパラメータを解析する
     * Principal型の検証と、アクセス宣言に対するnull許容性・トークン種別の整合性をチェックする
     */
    private fun analyzeCaller(
        className: String,
        function: KFunction<*>,
        param: KParameter,
        access: EndpointAccess?,
        errors: MutableList<RegistrationError>
    ): ParameterInfo? {
        val paramName = param.name ?: "unknown"

        // バインド可能なPrincipal型を判定
        val kind = when (param.type.jvmErasure) {
            Principal::class -> CallerKind.ANY
            Principal.User::class -> CallerKind.USER
            Principal.Service::class -> CallerKind.SERVICE
            else -> {
                errors += RegistrationError.UnsupportedParameterType(
                    className, function.name, paramName,
                    param.type.toString(),
                    listOf("Principal", "Principal.User", "Principal.Service")
                )
                return null
            }
        }

        val nullable = param.type.isMarkedNullable

        when (access) {
            is EndpointAccess.Public -> {
                // 公開エンドポイントでは未認証時にnullが渡されるため、nullable必須
                if (!nullable) {
                    errors += RegistrationError.CallerNullabilityMismatch(className, function.name, paramName)
                    return null
                }
            }

            is EndpointAccess.Authenticated -> {
                // 認証必須エンドポイントでは認証が保証されるため、non-nullable必須
                if (nullable) {
                    errors += RegistrationError.CallerNullabilityMismatch(className, function.name, paramName)
                    return null
                }

                // Principal型とcallers設定の整合性チェック
                // 例: Principal.User型なのにサービストークンも許可されていると、実行時にバインド不能になる
                val mismatchReason = when (kind) {
                    CallerKind.USER ->
                        if (access.callers != setOf(CallerType.USER))
                            "Principal.User requires callers = [CallerType.USER] (got ${access.callers})" else null

                    CallerKind.SERVICE ->
                        if (access.callers != setOf(CallerType.SERVICE))
                            "Principal.Service requires callers = [CallerType.SERVICE] (got ${access.callers})" else null

                    CallerKind.ANY -> null
                }
                if (mismatchReason != null) {
                    errors += RegistrationError.CallerTypeMismatch(className, function.name, paramName, mismatchReason)
                    return null
                }
            }

            null -> return null // アクセス宣言が不正な場合は既にエラー済み
        }

        return ParameterInfo.Caller(kind, nullable)
    }

    /**
     * @PlayerParamパラメータを解析する
     * OfflinePlayer型のみ、@Authenticatedエンドポイントのみで使用可能
     */
    private fun analyzePlayerParam(
        className: String,
        function: KFunction<*>,
        param: KParameter,
        access: EndpointAccess?,
        segments: List<PathSegment>,
        path: String,
        errors: MutableList<RegistrationError>
    ): ParameterInfo? {
        val paramName = param.name ?: "unknown"
        val annotation = param.findAnnotation<PlayerParam>()!!

        // @PlayerParamは認証必須エンドポイントでのみ使用可能
        // （公開エンドポイントではアクセスポリシーの主体が存在しないため）
        if (access !is EndpointAccess.Authenticated) {
            if (access is EndpointAccess.Public) {
                errors += RegistrationError.InvalidAccessConfiguration(
                    className, function.name, "@PlayerParam requires @Authenticated (found @Public)"
                )
            }
            return null
        }

        // バインド型はOfflinePlayerのみ（オンライン必須ならhandler内でonlinePlayerを確認する）
        if (param.type.jvmErasure != OfflinePlayer::class) {
            errors += RegistrationError.UnsupportedParameterType(
                className, function.name, paramName,
                param.type.toString(), listOf("OfflinePlayer")
            )
            return null
        }

        // パスに対応するセグメントが存在するかチェック
        if (segments.none { it is PathSegment.Param && it.name == annotation.value }) {
            errors += RegistrationError.PathParameterMismatch(
                className, function.name, paramName, annotation.value, path
            )
            return null
        }

        // SELF_ONLYポリシーはユーザートークン前提のため、SERVICE専用エンドポイントでは意味を成さない
        if (annotation.access == party.morino.mineauth.api.PlayerAccess.SELF_ONLY &&
            access.callers == setOf(CallerType.SERVICE)
        ) {
            errors += RegistrationError.InvalidAccessConfiguration(
                className, function.name,
                "PlayerAccess.SELF_ONLY requires USER callers (got ${access.callers})"
            )
            return null
        }

        return ParameterInfo.TargetPlayer(annotation.value, annotation.access)
    }

    /**
     * 戻り値型を解析する
     * Either<HttpError, T>の場合はTをレスポンス型として抽出する
     *
     * @return レスポンス型とEitherかどうかのペア
     */
    private fun analyzeReturnType(
        className: String,
        function: KFunction<*>,
        errors: MutableList<RegistrationError>
    ): Pair<KType, Boolean> {
        val returnType = function.returnType
        val classifierName = (returnType.classifier as? KClass<*>)?.qualifiedName

        // ArrowのEitherかどうかを判定
        // shade/relocate対応のため接尾辞比較とするが、パッケージ境界を含めて誤判定を防ぐ
        if (classifierName != null &&
            (classifierName == "arrow.core.Either" || classifierName.endsWith(".arrow.core.Either"))
        ) {
            // 左側の型はHttpErrorである必要がある
            val leftType = returnType.arguments.getOrNull(0)?.type
            if (leftType?.jvmErasure != HttpError::class) {
                errors += RegistrationError.InvalidAccessConfiguration(
                    className, function.name,
                    "Either return type must be Either<HttpError, T> (got left type $leftType)"
                )
            }
            val rightType = returnType.arguments.getOrNull(1)?.type ?: typeOf<Any?>()
            return rightType to true
        }

        return returnType to false
    }

    /**
     * @Routeプレフィックスとメソッドのパスを結合して正規化する
     * `:id`形式を`{id}`形式に変換し、スラッシュの重複を除去する
     *
     * @param prefix クラスレベルの@Routeプレフィックス
     * @param rawPath メソッドレベルのパス
     * @return 正規化されたパス（先頭スラッシュ付き、末尾スラッシュなし）
     */
    private fun normalizePath(prefix: String, rawPath: String): String {
        val combined = "/${prefix.trim('/')}/${rawPath.trim('/')}"
            .replace(Regex(":([a-zA-Z_][a-zA-Z0-9_]*)"), "{$1}")
            .replace(Regex("/+"), "/")
        return if (combined.length > 1) combined.trimEnd('/') else combined
    }

    /**
     * 正規化済みパスをセグメントのリストに分解する
     *
     * @param path 正規化済みパス
     * @return コンパイル済みセグメントのリスト
     */
    private fun parseSegments(path: String): List<PathSegment> =
        path.split('/').filter { it.isNotEmpty() }.map { segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) {
                PathSegment.Param(segment.removeSurrounding("{", "}"))
            } else {
                PathSegment.Literal(segment)
            }
        }
}
