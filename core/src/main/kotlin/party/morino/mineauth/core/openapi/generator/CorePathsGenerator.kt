package party.morino.mineauth.core.openapi.generator

import party.morino.mineauth.core.openapi.model.MediaType
import party.morino.mineauth.core.openapi.model.Operation
import party.morino.mineauth.core.openapi.model.Parameter
import party.morino.mineauth.core.openapi.model.PathItem
import party.morino.mineauth.core.openapi.model.RequestBody
import party.morino.mineauth.core.openapi.model.Response
import party.morino.mineauth.core.openapi.model.Schema
import party.morino.mineauth.core.openapi.model.Tag

/**
 * MineAuthコアのエンドポイント定義をOpenAPIパスとして生成する
 * 動的に登録されるアドオンエンドポイントとは異なり、
 * コアに組み込まれたエンドポイントを静的に定義する
 */
class CorePathsGenerator {

    companion object {
        // タグ名定数
        private const val TAG_SERVER = "server"
        private const val TAG_PLUGINS = "plugins"
        private const val TAG_OAUTH = "oauth2"
        private const val TAG_OIDC_DISCOVERY = "oidc-discovery"
    }

    /**
     * コアエンドポイントのパス定義を生成する
     *
     * @return パスとPathItemのマップ
     */
    fun generate(): Map<String, PathItem> {
        return buildMap {
            // サーバー情報エンドポイント
            putAll(generateServerPaths())
            // プラグイン管理エンドポイント
            putAll(generatePluginPaths())
            // OAuth2/OIDCエンドポイント
            putAll(generateOAuthPaths())
            // OIDC Discoveryエンドポイント
            putAll(generateDiscoveryPaths())
        }
    }

    /**
     * コアエンドポイント用のタグ一覧を生成する
     */
    fun generateTags(): List<Tag> {
        return listOf(
            Tag(name = TAG_SERVER, description = "Server information endpoints"),
            Tag(name = TAG_PLUGINS, description = "Plugin management endpoints"),
            Tag(name = TAG_OAUTH, description = "OAuth2 authentication endpoints"),
            Tag(name = TAG_OIDC_DISCOVERY, description = "OpenID Connect Discovery endpoints"),
        )
    }

    /**
     * /api/v1/commons/server 配下のエンドポイントを生成する
     */
    private fun generateServerPaths(): Map<String, PathItem> {
        // ファイルハッシュのスキーマ定義
        val fileHashSchema = Schema(
            type = "object",
            properties = mapOf(
                "sha1" to Schema(type = "string", description = "SHA-1 hash"),
                "sha256" to Schema(type = "string", description = "SHA-256 hash"),
            ),
            required = listOf("sha1", "sha256"),
        )

        // ファイル情報のスキーマ定義
        val fileSchema = Schema(
            type = "object",
            properties = mapOf(
                "name" to Schema(type = "string", description = "JAR file name"),
                "hash" to fileHashSchema,
            ),
            required = listOf("name", "hash"),
        )

        // 依存関係のスキーマ定義
        val dependenciesSchema = Schema(
            type = "object",
            properties = mapOf(
                "required" to Schema(type = "array", items = Schema(type = "string"), description = "Required dependency plugin names"),
                "soft" to Schema(type = "array", items = Schema(type = "string"), description = "Soft (optional) dependency plugin names"),
            ),
        )

        // プラグイン情報のスキーマ定義
        val pluginInfoSchema = Schema(
            type = "object",
            properties = mapOf(
                "name" to Schema(type = "string", description = "Plugin name"),
                "version" to Schema(type = "string", description = "Plugin version"),
                "description" to Schema(type = "string", description = "Plugin description", nullable = true),
                "authors" to Schema(type = "array", items = Schema(type = "string"), description = "Plugin authors"),
                "website" to Schema(type = "string", description = "Plugin website", nullable = true),
                "dependencies" to dependenciesSchema,
                "file" to Schema(type = "object", nullable = true, properties = fileSchema.properties, required = fileSchema.required),
            ),
            required = listOf("name", "version"),
        )

        // プレイヤー一覧のスキーマ定義
        val profileSchema = Schema(
            type = "object",
            properties = mapOf(
                "username" to Schema(type = "string", description = "Player name"),
                "id" to Schema(type = "string", format = "uuid", description = "Player UUID"),
            ),
            required = listOf("username", "id"),
        )

        return mapOf(
            "/api/v1/commons/server/players" to PathItem(
                get = Operation(
                    summary = "Get online players",
                    description = "Returns a list of currently online players with their profile data.",
                    operationId = "get_server_players",
                    tags = listOf(TAG_SERVER),
                    responses = mapOf(
                        "200" to Response(
                            description = "List of online players",
                            content = mapOf(
                                "application/json" to MediaType(
                                    schema = Schema(
                                        type = "array",
                                        items = profileSchema,
                                    )
                                )
                            ),
                        ),
                    ),
                ),
            ),
            "/api/v1/commons/server/plugins" to PathItem(
                get = Operation(
                    summary = "Get installed plugins",
                    description = "Returns detailed information about installed plugins on the server. Requires service account authentication.",
                    operationId = "get_server_plugins",
                    tags = listOf(TAG_SERVER),
                    security = listOf(mapOf("serviceToken" to emptyList())),
                    responses = mapOf(
                        "200" to Response(
                            description = "List of installed plugins with detailed information",
                            content = mapOf(
                                "application/json" to MediaType(
                                    schema = Schema(
                                        type = "array",
                                        items = pluginInfoSchema,
                                    )
                                )
                            ),
                        ),
                        "401" to Response(description = "Unauthorized - valid service account token required"),
                    ),
                ),
            ),
        )
    }

    /**
     * /api/v1/plugins 配下のエンドポイントを生成する
     */
    private fun generatePluginPaths(): Map<String, PathItem> {
        return mapOf(
            "/api/v1/plugins/availableIntegrations" to PathItem(
                get = Operation(
                    summary = "Get available integrations",
                    description = "Returns the built-in integrations available on this server " +
                        "and the endpoints dynamically registered by addons via MineAuthApi.register().",
                    operationId = "get_available_integrations",
                    tags = listOf(TAG_PLUGINS),
                    responses = mapOf(
                        "200" to Response(
                            description = "Built-in integrations and registered addon endpoints",
                            content = mapOf(
                                "application/json" to MediaType(schema = availableIntegrationsSchema())
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    /**
     * availableIntegrationsレスポンスのスキーマを生成する
     * （AvailableIntegrationsResponse / RegisteredPluginData / RegisteredEndpointData に対応）
     */
    private fun availableIntegrationsSchema(): Schema {
        val endpointSchema = Schema(
            type = "object",
            properties = mapOf(
                "method" to Schema(type = "string", description = "HTTP method"),
                "path" to Schema(type = "string", description = "Full path including the base path"),
                "access" to Schema(
                    type = "string",
                    enum = listOf("PUBLIC", "AUTHENTICATED"),
                    description = "Whether the endpoint requires authentication",
                ),
                "permission" to Schema(
                    type = "string",
                    nullable = true,
                    description = "Required permission node (authenticated endpoints only; omitted when none)",
                ),
                "callers" to Schema(
                    type = "array",
                    nullable = true,
                    items = Schema(type = "string", enum = listOf("USER", "SERVICE")),
                    description = "Token types allowed to call the endpoint (authenticated endpoints only)",
                ),
            ),
            required = listOf("method", "path", "access"),
        )
        val pluginSchema = Schema(
            type = "object",
            properties = mapOf(
                "namespace" to Schema(type = "string", description = "URL namespace (e.g. tickets)"),
                "plugin" to Schema(type = "string", description = "Name of the plugin that owns the namespace"),
                "basePath" to Schema(type = "string", description = "Mounted base path (e.g. /api/v1/plugins/tickets)"),
                "endpoints" to Schema(type = "array", items = endpointSchema),
            ),
            required = listOf("namespace", "plugin", "basePath", "endpoints"),
        )
        return Schema(
            type = "object",
            properties = mapOf(
                "integrations" to Schema(
                    type = "array",
                    items = Schema(type = "string"),
                    description = "Names of built-in integrations that are available (e.g. LuckPerms)",
                ),
                "plugins" to Schema(
                    type = "array",
                    items = pluginSchema,
                    description = "Addon namespaces currently mounted, sorted by namespace",
                ),
            ),
            required = listOf("integrations", "plugins"),
        )
    }

    /**
     * OAuth2/OIDCエンドポイントを生成する
     */
    private fun generateOAuthPaths(): Map<String, PathItem> {
        // トークンレスポンスのスキーマ
        val tokenResponseSchema = Schema(
            type = "object",
            properties = mapOf(
                "access_token" to Schema(type = "string", description = "Access token (JWT)"),
                "token_type" to Schema(type = "string", description = "Token type (Bearer)"),
                "expires_in" to Schema(type = "integer", description = "Token expiration time in seconds"),
                "refresh_token" to Schema(type = "string", description = "Refresh token (JWT)"),
                "id_token" to Schema(type = "string", description = "OpenID Connect ID Token (JWT)", nullable = true),
                "scope" to Schema(type = "string", description = "Granted scopes (space-separated)", nullable = true),
            ),
            required = listOf("access_token", "token_type", "expires_in"),
        )

        // OAuthエラーレスポンスのスキーマ
        val oauthErrorSchema = Schema(
            type = "object",
            properties = mapOf(
                "error" to Schema(type = "string", description = "Error code (RFC 6749)"),
                "error_description" to Schema(type = "string", description = "Error description"),
            ),
            required = listOf("error"),
        )

        return mapOf(
            "/oauth2/authorize" to PathItem(
                get = Operation(
                    summary = "Authorization endpoint",
                    description = "Displays the OAuth2 authorization screen. RFC 6749 Section 4.1.1.",
                    operationId = "get_oauth2_authorize",
                    tags = listOf(TAG_OAUTH),
                    parameters = listOf(
                        Parameter(name = "response_type", location = "query", required = true, schema = Schema(type = "string"), description = "Must be 'code'"),
                        Parameter(name = "client_id", location = "query", required = true, schema = Schema(type = "string"), description = "Client identifier"),
                        Parameter(name = "redirect_uri", location = "query", required = true, schema = Schema(type = "string"), description = "Redirect URI"),
                        Parameter(name = "scope", location = "query", required = true, schema = Schema(type = "string"), description = "Requested scopes (space-separated)"),
                        Parameter(name = "state", location = "query", required = true, schema = Schema(type = "string"), description = "CSRF protection state"),
                        Parameter(name = "code_challenge", location = "query", required = true, schema = Schema(type = "string"), description = "PKCE code challenge"),
                        Parameter(name = "code_challenge_method", location = "query", required = true, schema = Schema(type = "string"), description = "PKCE method. Must be S256"),
                        Parameter(name = "nonce", location = "query", required = false, schema = Schema(type = "string"), description = "OIDC nonce for replay protection"),
                        Parameter(name = "prompt", location = "query", required = false, schema = Schema(type = "string"), description = "OIDC prompt. 'none' fails with login_required"),
                    ),
                    responses = mapOf(
                        "200" to Response(description = "Authorization screen (HTML)"),
                        "302" to Response(description = "Redirect to the validated redirect_uri with an error"),
                        "400" to Response(description = "client_id or redirect_uri is missing or invalid. No redirect is performed"),
                    ),
                ),
                post = Operation(
                    summary = "Authorization request or login submission",
                    description = "A form body containing username or password is a login submission and returns the authorization code via redirect. Any other POST is an authorization request and displays the authorization screen. OIDC Core Section 3.1.2.1.",
                    operationId = "post_oauth2_authorize",
                    tags = listOf(TAG_OAUTH),
                    requestBody = RequestBody(
                        content = mapOf(
                            "application/x-www-form-urlencoded" to MediaType(
                                schema = Schema(
                                    type = "object",
                                    properties = mapOf(
                                        "username" to Schema(type = "string"),
                                        "password" to Schema(type = "string"),
                                        "response_type" to Schema(type = "string"),
                                        "client_id" to Schema(type = "string"),
                                        "redirect_uri" to Schema(type = "string"),
                                        "scope" to Schema(type = "string"),
                                        "state" to Schema(type = "string"),
                                        "code_challenge" to Schema(type = "string"),
                                        "code_challenge_method" to Schema(type = "string", description = "Must be S256"),
                                        "nonce" to Schema(type = "string"),
                                        "prompt" to Schema(type = "string"),
                                    ),
                                    required = listOf("response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method"),
                                )
                            )
                        ),
                        required = true,
                    ),
                    responses = mapOf(
                        "200" to Response(description = "Authorization screen (HTML)"),
                        "302" to Response(description = "Redirect with authorization code or error"),
                        "400" to Response(description = "client_id or redirect_uri is missing or invalid. No redirect is performed"),
                    ),
                ),
            ),
            "/oauth2/token" to PathItem(
                post = Operation(
                    summary = "Token endpoint",
                    description = "Issues access tokens. Supports authorization_code and refresh_token grant types. RFC 6749 Section 4.1.3. " +
                        "Confidential clients authenticate with client_secret_basic or client_secret_post. " +
                        "Public clients send client_id in the body without client_secret.",
                    operationId = "post_oauth2_token",
                    tags = listOf(TAG_OAUTH),
                    requestBody = RequestBody(
                        content = mapOf(
                            "application/x-www-form-urlencoded" to MediaType(
                                schema = Schema(
                                    type = "object",
                                    properties = mapOf(
                                        "grant_type" to Schema(type = "string", description = "authorization_code or refresh_token"),
                                        "code" to Schema(type = "string", description = "Authorization code (for authorization_code grant)"),
                                        "redirect_uri" to Schema(type = "string"),
                                        "code_verifier" to Schema(type = "string", description = "PKCE code verifier"),
                                        "refresh_token" to Schema(type = "string", description = "Refresh token (for refresh_token grant)"),
                                    ) + clientSecretPostProperties() + mapOf(
                                        // Publicクライアントもclient_secretを付けずに本文でclient_idを送る
                                        "client_id" to Schema(type = "string", description = "Client identifier. Required for public clients and with client_secret_post"),
                                    ),
                                    // client_secret_basicではクレデンシャルをヘッダーで送るため、grant_typeだけが常に必須になる
                                    required = listOf("grant_type"),
                                )
                            )
                        ),
                        required = true,
                    ),
                    responses = mapOf(
                        "200" to Response(
                            description = "Token response",
                            content = mapOf("application/json" to MediaType(schema = tokenResponseSchema)),
                        ),
                        "400" to Response(
                            description = "OAuth error",
                            content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
                        ),
                        "401" to Response(description = "Invalid client authentication"),
                        "429" to rateLimitedResponse(oauthErrorSchema),
                        // クライアントの登録情報を読めなかった場合（どちらのグラントでも）と、リフレッシュトークンの状態を読み取れない場合・
                        // ローテーションに失敗した場合は、トークンを発行せずにserver_errorを返す
                        "500" to Response(
                            description = "server_error. No tokens were issued. The client registration could not be read during client authentication, " +
                                "or, for the refresh_token grant, the state of the refresh token could not be read or the refresh token could not be rotated. " +
                                "The client may retry the same request, with the same authorization code or refresh token",
                            content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
                        ),
                        "503" to verifierBusyResponse(oauthErrorSchema),
                    ),
                ),
            ),
            "/oauth2/revoke" to PathItem(
                post = Operation(
                    summary = "Token revocation endpoint",
                    description = "Revokes an access token or refresh token. RFC 7009. " +
                        "Requires confidential client authentication with client_secret_basic or client_secret_post.",
                    operationId = "post_oauth2_revoke",
                    tags = listOf(TAG_OAUTH),
                    requestBody = RequestBody(
                        content = mapOf(
                            "application/x-www-form-urlencoded" to MediaType(
                                schema = Schema(
                                    type = "object",
                                    properties = mapOf(
                                        "token" to Schema(type = "string", description = "Token to revoke"),
                                        "token_type_hint" to Schema(type = "string", description = "access_token or refresh_token"),
                                    ) + clientSecretPostProperties(),
                                    // client_secret_basicではクレデンシャルをヘッダーで送るため、tokenだけが常に必須になる
                                    required = listOf("token"),
                                )
                            )
                        ),
                        required = true,
                    ),
                    responses = mapOf(
                        "200" to Response(description = "Token revoked successfully, or there was nothing this client could revoke"),
                        "400" to Response(
                            description = "OAuth error",
                            content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
                        ),
                        "401" to Response(description = "Invalid client authentication"),
                        "429" to rateLimitedResponse(oauthErrorSchema),
                        // クライアントの登録情報を読めなかった場合も、認証の失敗ではないのでserver_errorを返す
                        "500" to Response(
                            description = "server_error. The client registration could not be read, or the revocation could not be recorded in full. " +
                                "In the second case the token or other tokens of its grant may still be valid. In both cases the client should retry",
                            content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
                        ),
                        "503" to verifierBusyResponse(oauthErrorSchema),
                    ),
                ),
            ),
            "/oauth2/introspect" to introspectPathItem(oauthErrorSchema),
            "/oauth2/end_session" to endSessionPathItem(oauthErrorSchema),
            // OIDC Core Section 5.3: UserInfoはGETとPOSTで同じ応答を返す
            "/oauth2/userinfo" to PathItem(
                get = userInfoOperation("get_oauth2_userinfo"),
                post = userInfoOperation("post_oauth2_userinfo"),
            ),
        )
    }

    /**
     * client_secret_post で送るフォームパラメータを生成する
     * client_secret_basic ではヘッダーで送るので、呼び出し側のスキーマでは必須にしないこと
     *
     * @return パラメータ名とスキーマのマップ
     */
    private fun clientSecretPostProperties(): Map<String, Schema> = mapOf(
        "client_id" to Schema(type = "string", description = "Client identifier. Required with client_secret_post"),
        "client_secret" to Schema(type = "string", description = "Client secret. Required with client_secret_post"),
    )

    /**
     * トークンイントロスペクションエンドポイントのPathItemを生成する（RFC 7662）
     *
     * @param oauthErrorSchema OAuthエラーレスポンスのスキーマ
     * @return /oauth2/introspect のPathItem
     */
    private fun introspectPathItem(oauthErrorSchema: Schema): PathItem {
        val errorContent = mapOf("application/json" to MediaType(schema = oauthErrorSchema))

        return PathItem(
            post = Operation(
                summary = "Token introspection endpoint",
                description = "Reports the state and metadata of a token. RFC 7662. " +
                    "Requires confidential client authentication with client_secret_basic or client_secret_post, " +
                    "and a server administrator must have granted the client the introspection permission.",
                operationId = "post_oauth2_introspect",
                tags = listOf(TAG_OAUTH),
                requestBody = RequestBody(
                    content = mapOf(
                        "application/x-www-form-urlencoded" to MediaType(
                            schema = Schema(
                                type = "object",
                                properties = mapOf(
                                    "token" to Schema(type = "string", description = "Token to introspect"),
                                    "token_type_hint" to Schema(type = "string", description = "access_token or refresh_token. Does not change the result"),
                                ) + clientSecretPostProperties(),
                                // client_secret_basicではクレデンシャルをヘッダーで送るため、tokenだけが常に必須になる
                                required = listOf("token"),
                            )
                        )
                    ),
                    required = true,
                ),
                responses = mapOf(
                    "200" to Response(
                        description = "Token state. An inactive token returns only active=false",
                        content = mapOf("application/json" to MediaType(schema = introspectionResponseSchema())),
                    ),
                    "400" to Response(description = "OAuth error", content = errorContent),
                    "401" to Response(description = "Invalid client authentication", content = errorContent),
                    "403" to Response(
                        description = "unauthorized_client. The client has not been granted the introspection permission",
                        content = errorContent,
                    ),
                    "429" to rateLimitedResponse(oauthErrorSchema),
                    // クライアントの登録情報を読めなかった場合は、認証の失敗ではないのでserver_errorを返す
                    "500" to Response(
                        description = "server_error. The client registration could not be read. The client should retry",
                        content = errorContent,
                    ),
                    "503" to verifierBusyResponse(oauthErrorSchema),
                ),
            ),
        )
    }

    /**
     * 流量制限時の429レスポンスを生成する
     * トークンエンドポイントはリクエスト数とバックオフ、失効とイントロスペクションはバックオフでこの応答を返す
     *
     * @param oauthErrorSchema OAuthエラーレスポンスのスキーマ
     * @return 429のレスポンス
     */
    private fun rateLimitedResponse(oauthErrorSchema: Schema): Response = Response(
        description = "Rate limited (temporarily_unavailable). The Retry-After header gives the wait in seconds",
        content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
    )

    /**
     * Argon2の同時実行数の上限でクライアントシークレットを照合できなかった場合の503レスポンスを生成する
     * トークン・失効・イントロスペクションの各エンドポイントが、シークレットを照合せずにこの応答を返す（失敗の試行としては数えない）
     *
     * @param oauthErrorSchema OAuthエラーレスポンスのスキーマ
     * @return 503のレスポンス
     */
    private fun verifierBusyResponse(oauthErrorSchema: Schema): Response = Response(
        description = "Server busy (temporarily_unavailable). The client secret could not be verified because no verification slot became free. Retry after the Retry-After header, which is always 1 second",
        content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
    )

    /**
     * イントロスペクションレスポンスのスキーマを生成する（IntrospectionResponse に対応）
     *
     * @return レスポンスのスキーマ
     */
    private fun introspectionResponseSchema(): Schema = Schema(
        type = "object",
        properties = mapOf(
            "active" to Schema(type = "boolean", description = "Whether the token is currently active"),
            "scope" to Schema(type = "string", description = "Granted scopes (space-separated)"),
            "client_id" to Schema(type = "string", description = "Client the token was issued to"),
            "username" to Schema(type = "string", description = "Minecraft player name"),
            "token_type" to Schema(type = "string", description = "Bearer for access and service account tokens. Omitted for refresh tokens"),
            "exp" to Schema(type = "integer", format = "int64", description = "Expiration time (Unix seconds)"),
            "iat" to Schema(type = "integer", format = "int64", description = "Issued-at time (Unix seconds)"),
            "nbf" to Schema(type = "integer", format = "int64", description = "Not-before time (Unix seconds)"),
            "sub" to Schema(type = "string", description = "Player UUID"),
            "aud" to Schema(type = "string", description = "Audience the token was intended for"),
            "iss" to Schema(type = "string", description = "Issuer identifier"),
            "jti" to Schema(type = "string", description = "JWT identifier"),
        ),
        required = listOf("active"),
    )

    /**
     * RP-Initiated LogoutエンドポイントのPathItemを生成する
     * OIDC RP-Initiated Logout 1.0 Section 2: GETとPOSTはパラメータの渡し方だけが異なる
     *
     * @param oauthErrorSchema OAuthエラーレスポンスのスキーマ
     * @return /oauth2/end_session のPathItem
     */
    private fun endSessionPathItem(oauthErrorSchema: Schema): PathItem {
        val summary = "End session endpoint"
        val description = "Starts RP-Initiated Logout. OpenID Connect RP-Initiated Logout 1.0. " +
            "MineAuth keeps no server-side session, so the client discards its tokens and uses /oauth2/revoke to invalidate them."

        // パラメータ名と説明はGETのクエリとPOSTのフォームで共通にする
        val parameterDescriptions = mapOf(
            "id_token_hint" to "Previously issued ID Token. Required when post_logout_redirect_uri is specified",
            "client_id" to "Client identifier. When sent with id_token_hint, it must be one of the ID Token's audiences",
            "post_logout_redirect_uri" to "URL to redirect to after logout. It must match a registered redirect URI of the client",
            "state" to "Opaque value returned in the redirect",
        )

        val responses = mapOf(
            "200" to Response(
                description = "Logout completed. Returned when post_logout_redirect_uri is not specified",
                content = mapOf(
                    "application/json" to MediaType(
                        schema = Schema(
                            type = "object",
                            properties = mapOf("message" to Schema(type = "string", description = "Completion message")),
                            required = listOf("message"),
                        )
                    )
                ),
            ),
            "302" to Response(description = "Redirect to post_logout_redirect_uri with state, if provided"),
            "400" to Response(
                description = "invalid_request. The id_token_hint, client_id or post_logout_redirect_uri is invalid. No redirect is performed",
                content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
            ),
        )

        return PathItem(
            get = Operation(
                summary = summary,
                description = description,
                operationId = "get_oauth2_end_session",
                tags = listOf(TAG_OAUTH),
                parameters = parameterDescriptions.map { (name, parameterDescription) ->
                    Parameter(name = name, location = "query", schema = Schema(type = "string"), description = parameterDescription)
                },
                responses = responses,
            ),
            post = Operation(
                summary = summary,
                description = description,
                operationId = "post_oauth2_end_session",
                tags = listOf(TAG_OAUTH),
                requestBody = RequestBody(
                    content = mapOf(
                        "application/x-www-form-urlencoded" to MediaType(
                            schema = Schema(
                                type = "object",
                                properties = parameterDescriptions.mapValues { (_, parameterDescription) ->
                                    Schema(type = "string", description = parameterDescription)
                                },
                            )
                        )
                    ),
                ),
                responses = responses,
            ),
        )
    }

    /**
     * UserInfoエンドポイントのOperationを生成する
     * OpenAPIではoperationIdが一意である必要があるため、メソッドごとに指定する
     *
     * @param operationId メソッドごとのoperationId
     * @return UserInfoエンドポイントのOperation
     */
    private fun userInfoOperation(operationId: String): Operation {
        return Operation(
            summary = "UserInfo endpoint",
            description = "Returns claims about the authenticated user. " +
                "The access token must be sent in the Authorization header. OIDC Core Section 5.3.",
            operationId = operationId,
            tags = listOf(TAG_OAUTH),
            security = listOf(mapOf("oauth2" to listOf("openid"))),
            responses = mapOf(
                "200" to Response(
                    description = "User information",
                    content = mapOf(
                        "application/json" to MediaType(
                            schema = Schema(
                                type = "object",
                                properties = mapOf(
                                    "sub" to Schema(type = "string", description = "Player UUID"),
                                    "name" to Schema(type = "string", description = "Player name"),
                                    "preferred_username" to Schema(type = "string", description = "Player name"),
                                    "email" to Schema(type = "string", description = "Generated email address"),
                                    "roles" to Schema(type = "array", items = Schema(type = "string"), description = "LuckPerms groups"),
                                ),
                                required = listOf("sub"),
                            )
                        )
                    ),
                ),
                "401" to Response(
                    description = "Missing or invalid access token. " +
                        "The WWW-Authenticate header carries the Bearer challenge (RFC 6750 Section 3).",
                ),
            ),
        )
    }

    /**
     * OIDC Discoveryエンドポイントを生成する
     */
    private fun generateDiscoveryPaths(): Map<String, PathItem> {
        return mapOf(
            "/.well-known/openid-configuration" to PathItem(
                get = Operation(
                    summary = "OpenID Connect Discovery",
                    description = "Returns the OpenID Connect Discovery document. OpenID Connect Discovery 1.0.",
                    operationId = "get_openid_configuration",
                    tags = listOf(TAG_OIDC_DISCOVERY),
                    responses = mapOf(
                        "200" to Response(
                            description = "OIDC Discovery document",
                            content = mapOf("application/json" to MediaType(schema = Schema(type = "object"))),
                        ),
                    ),
                ),
            ),
            "/.well-known/jwks.json" to PathItem(
                get = Operation(
                    summary = "JSON Web Key Set",
                    description = "Returns the JWK Set for token verification. RFC 7517.",
                    operationId = "get_jwks",
                    tags = listOf(TAG_OIDC_DISCOVERY),
                    responses = mapOf(
                        "200" to Response(
                            description = "JWK Set",
                            content = mapOf("application/json" to MediaType(schema = Schema(type = "object"))),
                        ),
                        "404" to Response(description = "JWKS file not found"),
                    ),
                ),
            ),
        )
    }
}
