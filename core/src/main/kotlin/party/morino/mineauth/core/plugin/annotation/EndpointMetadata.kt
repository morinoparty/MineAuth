package party.morino.mineauth.core.plugin.annotation

import kotlinx.serialization.KSerializer
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.PlayerAccess
import party.morino.mineauth.core.plugin.serialization.PluginSerialization
import party.morino.mineauth.core.plugin.serialization.toResolvableJavaType
import java.lang.reflect.Method
import java.lang.reflect.Type
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaMethod

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
    ) : ParameterInfo() {
        /**
         * 利用側クラスローダで解決したデコーダ（[serializer]がnullの場合にのみ使用する）
         *
         * シリアライザ解決（`serializer(Type)`）はリクエストごとに行うと重いため、初回利用時に
         * 一度だけ解決して保持する。このオブジェクトはエンドポイントの登録解除と共に破棄されるため、
         * 利用側クラスローダへの参照を新たにリークさせることはない（[EndpointMetadata]の注記を参照）。
         */
        val consumerCodec: PluginSerialization.Codec by lazy {
            PluginSerialization.codec(consumerClassLoader, javaType)
        }
    }

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

    /**
     * 条件付きリクエスト情報（`ConditionalRequest`）の注入
     * `If-None-Match`ヘッダーを参照して条件付き304を実現するために使う（payloadなし）
     */
    data object Conditional : ParameterInfo()
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
 * @property returnsResponse 戻り値が`Response<T>`ラッパー（Eitherの右側も含む）かどうか。
 *   trueの場合、[responseType]はラップされた内側の型Tを指し、実行時にラッパーを展開して
 *   ヘッダー・ETag・条件付き304を処理する。
 *
 * ## リクエスト時に使う派生値のキャッシュ
 *
 * 本体プロパティ（`by lazy`）は、リクエストごとに繰り返すと重いリフレクション・シリアライザ解決の
 * 結果を登録単位で1回だけ計算して保持する。これらは利用側プラグインのクラス・クラスローダを
 * 強参照するが、このメタデータ自体が既に[handlerInstance]を強参照しており、登録解除
 * （`unregister()` / `PluginDisableEvent`）でテーブルごと破棄されるため、リークの種類を新たに
 * 増やすことはない。クラスローダをキーにしたグローバルキャッシュとは異なり、寿命が登録と一致する。
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
    val responseResolvableByCore: Boolean,
    val returnsResponse: Boolean
) {
    /** ハンドラークラスの完全修飾名（ログ・テレメトリ用、`KClass.qualifiedName`はリフレクションを伴うため1回だけ解決する） */
    val handlerClassName: String by lazy { handlerInstance::class.qualifiedName ?: "unknown" }

    /**
     * 呼び出し対象のJavaメソッド（Kotlinリフレクションからの変換は1回だけ行う）
     *
     * 非publicなハンドラークラス上のpublicメソッドにもアクセスできるよう、ここで一度だけ
     * アクセス可能化を試みる。失敗しても例外にはせず、呼び出し時の`IllegalAccessException`として
     * 実行ハンドラー側で500に変換される。
     */
    val javaMethod: Method? by lazy { method.javaMethod?.also { it.trySetAccessible() } }

    /**
     * ハンドラーが受け取る`Continuation`の実クラス（suspend関数のみ、通常関数ではnull）
     *
     * MineAuth本体の`kotlin.coroutines.Continuation`と同一クラスなら、クラスローダ間の橋渡し
     * （動的プロキシ）を省略できる。
     */
    val continuationClass: Class<*>? by lazy {
        if (isSuspending) javaMethod?.parameterTypes?.lastOrNull() else null
    }

    /**
     * ハンドラー側クラスローダから見える`EmptyCoroutineContext`インスタンス（suspend関数のみ）
     *
     * クラスローダが分裂している場合、MineAuth側の`CoroutineContext`を渡すとクラス不一致になるため、
     * ハンドラー側のインスタンスを登録単位で1回だけ解決しておく。
     */
    val handlerEmptyCoroutineContext: Any? by lazy {
        continuationClass?.classLoader
            ?.loadClass("kotlin.coroutines.EmptyCoroutineContext")
            ?.getField("INSTANCE")
            ?.get(null)
    }

    /** レスポンスの直列化に用いるJava型（suspend関数の`Object`縮退を補正済み、1回だけ解決する） */
    val responseJavaType: Type by lazy { responseType.toResolvableJavaType() }

    /**
     * 利用側クラスローダで解決したレスポンスのエンコーダ
     * [responseResolvableByCore]がfalseの場合にのみ使用し、初回利用時に一度だけ解決する
     */
    val consumerResponseCodec: PluginSerialization.Codec by lazy {
        PluginSerialization.codec(handlerInstance.javaClass.classLoader, responseJavaType)
    }

    /**
     * ルートの具体性を表すスコア
     * リテラル=1、パラメータ=0の文字列とすることで、辞書順比較が
     * 「左寄りのリテラルを優先」という直感的なルールになる（例: `/shops/mine` > `/shops/{id}`）
     */
    val specificity: String by lazy {
        pathSegments.joinToString("") { if (it is PathSegment.Literal) "1" else "0" }
    }
}
