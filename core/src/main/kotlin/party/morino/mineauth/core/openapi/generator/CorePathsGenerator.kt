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
                    description = "Returns a list of installed plugin names on the server.",
                    operationId = "get_server_plugins",
                    tags = listOf(TAG_SERVER),
                    responses = mapOf(
                        "200" to Response(
                            description = "List of plugin names",
                            content = mapOf(
                                "application/json" to MediaType(
                                    schema = Schema(
                                        type = "array",
                                        items = Schema(type = "string"),
                                    )
                                )
                            ),
                        ),
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
                    description = "Returns a list of available addon integrations registered with MineAuth.",
                    operationId = "get_available_integrations",
                    tags = listOf(TAG_PLUGINS),
                    responses = mapOf(
                        "200" to Response(
                            description = "List of integration names",
                            content = mapOf(
                                "application/json" to MediaType(
                                    schema = Schema(
                                        type = "array",
                                        items = Schema(type = "string"),
                                    )
                                )
                            ),
                        ),
                    ),
                ),
            ),
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
                        Parameter(name = "code_challenge", location = "query", required = false, schema = Schema(type = "string"), description = "PKCE code challenge"),
                        Parameter(name = "code_challenge_method", location = "query", required = false, schema = Schema(type = "string"), description = "PKCE method (S256)"),
                        Parameter(name = "nonce", location = "query", required = false, schema = Schema(type = "string"), description = "OIDC nonce for replay protection"),
                    ),
                    responses = mapOf(
                        "200" to Response(description = "Authorization screen (HTML)"),
                        "400" to Response(description = "Invalid request parameters"),
                    ),
                ),
                post = Operation(
                    summary = "Process authorization",
                    description = "Processes the authorization request with user credentials. Returns authorization code via redirect.",
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
                                        "code_challenge_method" to Schema(type = "string"),
                                    ),
                                    required = listOf("username", "password", "response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge"),
                                )
                            )
                        ),
                        required = true,
                    ),
                    responses = mapOf(
                        "302" to Response(description = "Redirect with authorization code or error"),
                    ),
                ),
            ),
            "/oauth2/token" to PathItem(
                post = Operation(
                    summary = "Token endpoint",
                    description = "Issues access tokens. Supports authorization_code and refresh_token grant types. RFC 6749 Section 4.1.3.",
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
                                        "client_id" to Schema(type = "string"),
                                        "client_secret" to Schema(type = "string", description = "Required for confidential clients"),
                                        "code_verifier" to Schema(type = "string", description = "PKCE code verifier"),
                                        "refresh_token" to Schema(type = "string", description = "Refresh token (for refresh_token grant)"),
                                    ),
                                    required = listOf("grant_type", "client_id"),
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
                    ),
                ),
            ),
            "/oauth2/revoke" to PathItem(
                post = Operation(
                    summary = "Token revocation endpoint",
                    description = "Revokes an access token or refresh token. RFC 7009.",
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
                                        "client_id" to Schema(type = "string"),
                                        "client_secret" to Schema(type = "string"),
                                    ),
                                    required = listOf("token", "client_id", "client_secret"),
                                )
                            )
                        ),
                        required = true,
                    ),
                    responses = mapOf(
                        "200" to Response(description = "Token revoked successfully"),
                        "400" to Response(
                            description = "OAuth error",
                            content = mapOf("application/json" to MediaType(schema = oauthErrorSchema)),
                        ),
                        "401" to Response(description = "Invalid client authentication"),
                    ),
                ),
            ),
            "/oauth2/userinfo" to PathItem(
                get = Operation(
                    summary = "UserInfo endpoint",
                    description = "Returns claims about the authenticated user. OIDC Core Section 5.3.",
                    operationId = "get_oauth2_userinfo",
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
                        "401" to Response(description = "Unauthorized"),
                    ),
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
