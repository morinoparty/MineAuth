package party.morino.mineauth.core.web.telemetry

import io.opentelemetry.api.common.AttributeKey

/**
 * MineAuthのテレメトリで使用するカスタム属性キーの定義
 *
 * OpenTelemetryのsemconvに存在しない属性（incubating属性やMineAuth固有の属性）を
 * ここに集約して定義する。incubating semconvアーティファクトへの依存を避けるため、
 * 標準名（service.namespace, host.name, db.*）も素のAttributeKeyとして定義している。
 *
 * PIIポリシー:
 * - プレイヤーUUIDは擬似匿名IDのため属性として許可（トレースの相関に必要）
 * - ユーザー名・パスワード・クライアントシークレット・トークン・認可コード・code_verifierは記録禁止
 */
object TelemetryAttributes {
    // ===== リソース属性 =====

    // サービスの論理グループ名（複数サーバー運用時の識別用）
    val SERVICE_NAMESPACE: AttributeKey<String> = AttributeKey.stringKey("service.namespace")

    // ホスト名
    val HOST_NAME: AttributeKey<String> = AttributeKey.stringKey("host.name")

    // Minecraftのバージョン（例: 1.21.11）
    val MINECRAFT_VERSION: AttributeKey<String> = AttributeKey.stringKey("minecraft.version")

    // サーバー実装名（例: Paper）
    val MINECRAFT_SERVER_BRAND: AttributeKey<String> = AttributeKey.stringKey("minecraft.server.brand")

    // プラグイン名
    val MINECRAFT_PLUGIN_NAME: AttributeKey<String> = AttributeKey.stringKey("minecraft.plugin.name")

    // ===== スパン属性（MineAuth固有） =====

    // OAuthクライアントID（非シークレット）
    val CLIENT_ID: AttributeKey<String> = AttributeKey.stringKey("mineauth.client.id")

    // OAuthグラントタイプ（authorization_code / refresh_token など）
    val OAUTH_GRANT_TYPE: AttributeKey<String> = AttributeKey.stringKey("mineauth.oauth.grant_type")

    // OAuthスコープ
    val OAUTH_SCOPE: AttributeKey<String> = AttributeKey.stringKey("mineauth.oauth.scope")

    // プレイヤーUUID（擬似匿名ID）
    val PLAYER_UUID: AttributeKey<String> = AttributeKey.stringKey("mineauth.player.uuid")

    // 発行するトークンの種類（access / refresh / id）
    val TOKEN_TYPE: AttributeKey<String> = AttributeKey.stringKey("mineauth.token.type")

    // 認証・検証の結果（valid / revoked / client_not_found / wrong_token_type など）
    val AUTH_RESULT: AttributeKey<String> = AttributeKey.stringKey("mineauth.auth.result")

    // OAuthエラーコード（invalid_grant / invalid_client など）
    val OAUTH_ERROR_CODE: AttributeKey<String> = AttributeKey.stringKey("mineauth.oauth.error_code")

    // アドオンルートのハンドラークラス名（どのアドオンの処理か識別する）
    val HANDLER_CLASS: AttributeKey<String> = AttributeKey.stringKey("mineauth.handler.class")

    // アドオンルートのエンドポイントパス
    val ENDPOINT_PATH: AttributeKey<String> = AttributeKey.stringKey("mineauth.endpoint.path")

    // アドオンルートのHTTPメソッド
    val ENDPOINT_METHOD: AttributeKey<String> = AttributeKey.stringKey("mineauth.endpoint.method")

    // アドオンエンドポイントのURL名前空間（例: vault）
    val PLUGIN_NAMESPACE: AttributeKey<String> = AttributeKey.stringKey("mineauth.plugin.namespace")

    // 名前空間を所有するプラグイン名（登録元アドオン）
    val PLUGIN_OWNER: AttributeKey<String> = AttributeKey.stringKey("mineauth.plugin.owner")

    // マッチしたエンドポイントのルートテンプレート（例: /api/v1/plugins/vault/shops/{id}）
    // 具体的なID値ではなくテンプレートを記録するため、カーディナリティが低くPIIも含まない
    val ROUTE_TEMPLATE: AttributeKey<String> = AttributeKey.stringKey("mineauth.route.template")

    // エンドポイントのアクセス区分（public / authenticated）
    val ENDPOINT_ACCESS: AttributeKey<String> = AttributeKey.stringKey("mineauth.endpoint.access")

    // 呼び出し元の種別（user / service / anonymous）
    val CALLER_TYPE: AttributeKey<String> = AttributeKey.stringKey("mineauth.auth.caller_type")

    // トークン種別ヒント（introspect / revokeのtoken_type_hint）
    val TOKEN_TYPE_HINT: AttributeKey<String> = AttributeKey.stringKey("mineauth.token.type_hint")

    // ===== DB属性（semconv準拠の名前） =====

    // データベースシステム名（sqlite / mysql）
    val DB_SYSTEM_NAME: AttributeKey<String> = AttributeKey.stringKey("db.system.name")

    // 実行する操作名（select / insert / update / delete）
    val DB_OPERATION_NAME: AttributeKey<String> = AttributeKey.stringKey("db.operation.name")

    // 対象テーブル名
    val DB_COLLECTION_NAME: AttributeKey<String> = AttributeKey.stringKey("db.collection.name")
}
