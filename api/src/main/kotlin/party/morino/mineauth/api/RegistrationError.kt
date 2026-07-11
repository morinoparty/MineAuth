package party.morino.mineauth.api

/**
 * エンドポイント登録時の検証エラーを表すsealed interface
 * 各エラーは発生箇所（ハンドラークラス・関数）と修正方法を[describe]で説明する
 */
sealed interface RegistrationError {

    /** 人間が読めるエラー説明（"Class#fn: 原因 -> 修正方法" の形式） */
    fun describe(): String

    /**
     * `@Public`と`@Authenticated`のどちらも付与されていない
     */
    data class MissingAccessDeclaration(
        val handlerClass: String,
        val function: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: no access declaration -> annotate with @Public or @Authenticated"
    }

    /**
     * `@Public`と`@Authenticated`が両方付与されている
     */
    data class ConflictingAccessDeclaration(
        val handlerClass: String,
        val function: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: both @Public and @Authenticated present -> keep exactly one"
    }

    /**
     * 複数のHTTPメソッドアノテーション（@Get/@Post等）が同一関数に付与されている
     */
    data class ConflictingHttpMethods(
        val handlerClass: String,
        val function: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: multiple HTTP method annotations -> keep exactly one of @Get/@Post/@Put/@Delete/@Patch"
    }

    /**
     * サポートされていないパラメータ型が使用されている
     */
    data class UnsupportedParameterType(
        val handlerClass: String,
        val function: String,
        val parameter: String,
        val declaredType: String,
        val supportedTypes: List<String>
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: parameter '$parameter' has unsupported type $declaredType -> use one of $supportedTypes"
    }

    /**
     * パラメータにアノテーションが付与されていない
     */
    data class MissingParameterAnnotation(
        val handlerClass: String,
        val function: String,
        val parameter: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: parameter '$parameter' has no annotation -> annotate with @Path/@Query/@QueryMap/@Body/@Caller/@PlayerParam"
    }

    /**
     * パラメータに複数のアノテーションが付与されている
     */
    data class ConflictingParameterAnnotations(
        val handlerClass: String,
        val function: String,
        val parameter: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: parameter '$parameter' has multiple parameter annotations -> keep exactly one"
    }

    /**
     * `@Path`/`@PlayerParam`で指定されたセグメント名がルートパスに存在しない
     */
    data class PathParameterMismatch(
        val handlerClass: String,
        val function: String,
        val parameter: String,
        val expectedSegment: String,
        val path: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: parameter '$parameter' expects path segment '{$expectedSegment}' but route path is '$path' -> add the segment to the path or fix the name"
    }

    /**
     * `@Caller`パラメータのnull許容性がアクセス宣言と一致しない
     * `@Public`ではnullable、`@Authenticated`ではnon-nullableである必要がある
     */
    data class CallerNullabilityMismatch(
        val handlerClass: String,
        val function: String,
        val parameter: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: @Caller parameter '$parameter' nullability mismatch -> must be nullable on @Public endpoints and non-nullable on @Authenticated endpoints"
    }

    /**
     * `@Caller`パラメータのPrincipal型が`callers`設定と一致しない
     * 例: Principal.User型なのにcallersが[SERVICE]のみ
     */
    data class CallerTypeMismatch(
        val handlerClass: String,
        val function: String,
        val parameter: String,
        val reason: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: @Caller parameter '$parameter' type mismatch: $reason"
    }

    /**
     * 同一名前空間内でルートが重複している
     */
    data class DuplicateRoute(
        val handlerClass: String,
        val function: String,
        val httpMethod: HttpMethod,
        val path: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: duplicate route $httpMethod $path -> change the path or remove one of the endpoints"
    }

    /**
     * アクセス設定が不正（例: @Public エンドポイントに @PlayerParam）
     */
    data class InvalidAccessConfiguration(
        val handlerClass: String,
        val function: String,
        val reason: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: invalid access configuration: $reason"
    }

    /**
     * 1つのエンドポイントに複数の`@Body`パラメータが定義されている
     */
    data class MultipleBodyParameters(
        val handlerClass: String,
        val function: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: multiple @Body parameters -> an endpoint can receive at most one request body"
    }

    /**
     * リクエストボディの型がシリアライズ不可能
     */
    data class BodyNotSerializable(
        val handlerClass: String,
        val function: String,
        val parameter: String,
        val reason: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: @Body parameter '$parameter' is not deserializable: $reason -> annotate the type with @Serializable"
    }

    /**
     * 戻り値（レスポンス）の型がシリアライズ不可能
     * `Either<HttpError, T>`の場合はT、それ以外は戻り値そのものの型を指す
     */
    data class ReturnTypeNotSerializable(
        val handlerClass: String,
        val function: String,
        val declaredType: String,
        val reason: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass#$function: return type $declaredType is not serializable: $reason -> annotate the type with @Serializable or return Unit"
    }

    /**
     * 名前空間が不正、または他のプラグインが使用中
     */
    data class InvalidNamespace(
        val namespace: String,
        val reason: String
    ) : RegistrationError {
        override fun describe(): String =
            "namespace '$namespace': $reason"
    }

    /**
     * ハンドラークラスにエンドポイントが1つも定義されていない
     */
    data class NoEndpoints(
        val handlerClass: String
    ) : RegistrationError {
        override fun describe(): String =
            "$handlerClass: no endpoint methods found -> annotate methods with @Get/@Post/@Put/@Delete/@Patch"
    }
}
