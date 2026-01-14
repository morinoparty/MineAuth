package party.morino.mineauth.core.plugin.annotation

import kotlin.reflect.KFunction
import kotlin.reflect.KType

/**
 * HTTPメソッドの種類を表す列挙型
 */
enum class HttpMethodType {
    GET, POST, PUT, DELETE, PATCH
}

/**
 * パラメータの種類を表すsealed class
 * 各パラメータタイプに対応した情報を保持する
 */
sealed class ParameterInfo {
    /**
     * パスパラメータ（例: /shops/{id} の id）
     * @property name パラメータ名
     * @property type パラメータの型
     */
    data class PathParam(val name: String, val type: KType) : ParameterInfo()

    /**
     * クエリパラメータ
     * @property type パラメータの型（通常はMap<String, String>）
     */
    data class QueryParams(val type: KType) : ParameterInfo()

    /**
     * リクエストボディ（JSONデシリアライズ）
     * @property type ボディの型
     */
    data class Body(val type: KType) : ParameterInfo()

    /**
     * 認証済みプレイヤー
     * @property type プレイヤーの型（Player, OfflinePlayer等）
     */
    data class AuthenticatedPlayer(val type: KType) : ParameterInfo()

    /**
     * アクセスユーザー（認証不要でも取得可能）
     * @property type プレイヤーの型
     */
    data class AccessPlayer(val type: KType) : ParameterInfo()
}

/**
 * エンドポイントのメタデータ
 * アノテーション解析の結果を格納し、ルート生成時に使用する
 *
 * @property method 対象のメソッド
 * @property handlerInstance ハンドラーインスタンス
 * @property path エンドポイントのパス（プラグインベースパスからの相対パス）
 * @property httpMethod HTTPメソッド
 * @property requiresAuthentication 認証が必要かどうか
 * @property requiredPermission 必要なパーミッション（nullの場合は不要）
 * @property parameters パラメータ情報のリスト（引数の順序を保持）
 * @property isSuspending suspending関数かどうか
 */
data class EndpointMetadata(
    val method: KFunction<*>,
    val handlerInstance: Any,
    val path: String,
    val httpMethod: HttpMethodType,
    val requiresAuthentication: Boolean,
    val requiredPermission: String?,
    val parameters: List<ParameterInfo>,
    val isSuspending: Boolean
)
