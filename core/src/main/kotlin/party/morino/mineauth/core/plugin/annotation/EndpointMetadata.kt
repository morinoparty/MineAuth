package party.morino.mineauth.core.plugin.annotation

import kotlinx.serialization.KSerializer
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.PlayerAccess
import java.lang.reflect.Type
import kotlin.reflect.KFunction
import kotlin.reflect.KType

/**
 * HTTPメソッドの種類を表す列挙型
 */
enum class HttpMethodType {
    GET, POST, PUT, DELETE, PATCH
}

/**
 * コンパイル済みのパスセグメントを表すsealed class
 * 登録時にパスを解析しておくことで、リクエスト時のマッチングを高速化する
 */
sealed class PathSegment {
    /**
     * リテラルセグメント（例: /shops の "shops"）
     * @property value セグメントの文字列
     */
    data class Literal(val value: String) : PathSegment()

    /**
     * パラメータセグメント（例: /{shopId} の "shopId"）
     * @property name パラメータ名
     */
    data class Param(val name: String) : PathSegment()
}

/**
 * エンドポイントのアクセス制御を表すsealed class
 * `@Public` xor `@Authenticated` の宣言から生成される
 */
sealed class EndpointAccess {
    /**
     * 認証不要の公開エンドポイント
     * @property reason 公開する理由（OpenAPIドキュメント用、任意）
     */
    data class Public(val reason: String?) : EndpointAccess()

    /**
     * 認証必須のエンドポイント
     * @property permission 必要なパーミッションノード（nullの場合は認証のみ）
     * @property callers 許可されるトークン種別
     */
    data class Authenticated(
        val permission: String?,
        val callers: Set<CallerType>
    ) : EndpointAccess()
}

/**
 * `@Caller`パラメータが要求するPrincipalの種類
 */
enum class CallerKind {
    /** Principal型（ユーザー・サービスどちらでも可） */
    ANY,

    /** Principal.User型（ユーザートークンのみ） */
    USER,

    /** Principal.Service型（サービストークンのみ） */
    SERVICE
}

/**
 * パラメータの種類を表すsealed class
 * 各パラメータタイプに対応した情報を保持する
 */
sealed class ParameterInfo {
    /**
     * パスパラメータ（例: /shops/{id} の id）
     * @property name パスセグメント名
     * @property type パラメータの型
     */
    data class PathParam(val name: String, val type: KType) : ParameterInfo()

    /**
     * 型付き単一クエリパラメータ
     * @property name クエリパラメータ名
     * @property type パラメータの型
     * @property optional Kotlin型がnullableの場合true（省略可能）
     */
    data class QueryParam(val name: String, val type: KType, val optional: Boolean) : ParameterInfo()

    /**
     * 全クエリパラメータのMap（エスケープハッチ）
     * @property type パラメータの型（Map<String, String>）
     */
    data class QueryMap(val type: KType) : ParameterInfo()

    /**
     * リクエストボディ（JSONデシリアライズ）
     *
     * 利用側プラグインがserializationをshadeしている場合、MineAuth本体のランタイムでは
     * シリアライザを解決できない。そのため[serializer]がnullのときは[consumerClassLoader]と
     * [javaType]を用いて利用側クラスローダで解決・デコードする。
     *
     * @property type ボディの型
     * @property serializer MineAuth本体で解決済みのシリアライザ（nullなら利用側クラスローダで解決）
     * @property javaType 利用側クラスローダで解決する際に使用する宣言上のJava型
     * @property consumerClassLoader ハンドラー（利用側プラグイン）のクラスローダー
     */
    data class Body(
        val type: KType,
        val serializer: KSerializer<Any?>?,
        val javaType: Type,
        val consumerClassLoader: ClassLoader
    ) : ParameterInfo()

    /**
     * 認証主体（Principal）の注入
     * @property kind 要求されるPrincipalの種類
     * @property optional nullableの場合true（@Publicエンドポイント用）
     */
    data class Caller(val kind: CallerKind, val optional: Boolean) : ParameterInfo()

    /**
     * パスセグメントから解決される対象プレイヤー
     * @property segment 解決に使用するパスセグメント名
     * @property access アクセスポリシー
     */
    data class TargetPlayer(val segment: String, val access: PlayerAccess) : ParameterInfo()
}

/**
 * エンドポイントのメタデータ
 * アノテーション解析の結果を格納し、ディスパッチ時に使用する
 *
 * @property method 対象のメソッド
 * @property handlerInstance ハンドラーインスタンス
 * @property path 正規化済みの相対パス（@Routeプレフィックスを含む、例: /shops/{shopId}）
 * @property pathSegments コンパイル済みのパスセグメント（マッチング用）
 * @property httpMethod HTTPメソッド
 * @property access アクセス制御情報
 * @property parameters パラメータ情報のリスト（引数の順序を保持）
 * @property isSuspending suspending関数かどうか
 * @property responseType レスポンスの型（Either<HttpError, T>の場合はT）
 * @property returnsEither 戻り値がArrowのEitherかどうか
 * @property responseResolvableByCore レスポンス型をMineAuth本体のランタイムで直列化できるか。
 *   登録時に一度だけ判定し、リクエスト時の直列化経路（本体 or 利用側クラスローダ）を決める。
 *   falseの場合は利用側がserializationをshadeしていると判断し利用側クラスローダで直列化する。
 */
data class EndpointMetadata(
    val method: KFunction<*>,
    val handlerInstance: Any,
    val path: String,
    val pathSegments: List<PathSegment>,
    val httpMethod: HttpMethodType,
    val access: EndpointAccess,
    val parameters: List<ParameterInfo>,
    val isSuspending: Boolean,
    val responseType: KType,
    val returnsEither: Boolean,
    val responseResolvableByCore: Boolean
)
