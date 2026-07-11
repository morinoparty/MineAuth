package party.morino.mineauth.core.plugin.serialization

import java.lang.reflect.Type

/**
 * 利用側プラグインのクラスローダーで直列化・逆直列化を行うためのヘルパー
 *
 * ## 背景（クラスローダ分裂問題）
 *
 * Paper は各プラグインに独立した `PluginClassLoader` を与える。利用側プラグインが
 * `kotlinx-serialization` を自前で同梱（shade、relocate なし）していると、その DTO の
 * 生成シリアライザ（`Xxx$$serializer`）は **利用側クラスローダの** `KSerializer` を実装する。
 *
 * MineAuth 本体が自分のクラスローダの `serializer(kType)` でシリアライザを解決すると、内部で
 * 行われる `生成シリアライザ as? KSerializer`（安全キャスト）が **別クラスローダの別 Class** の
 * ため `null` を返し、「Serializer for class 'Xxx' is not found」となる。
 *
 * ## 解決アプローチ
 *
 * 直列化処理そのものを **利用側のクラスローダ内で完結** させる。ポイントは 2 つ：
 *
 * 1. シリアライザ解決に `kotlin.reflect.KType` ではなく **`java.lang.reflect.Type`** を使う。
 *    JDK の `Type` はブートストラップローダー由来で全クラスローダで共有されるため、
 *    MineAuth 側で構築した `Type`（利用側の `Class` を参照する）を、利用側の
 *    `SerializersKt.serializer(Type)` にそのまま渡せる。ジェネリクス（`List<Foo>` 等）も
 *    利用側の内部処理で解決される。
 * 2. `Json` インスタンスも利用側クラスローダのものを使う。利用側のシリアライザを MineAuth 側の
 *    `Json`（＝別クラスローダの `Encoder`）で駆動すると、シリアライザが期待する `Encoder`
 *    インターフェースの Class 不一致で失敗するため、`Encoder` を含む全ての直列化機構を
 *    利用側に揃える必要がある。
 *
 * これらはすべてリフレクション経由で行い、MineAuth はコンパイル時に利用側の型へ依存しない。
 */
object PluginSerialization {

    // 注意: リフレクションハンドルをクラスローダごとにキャッシュしたくなるが、
    // キャッシュ値（[Handles]）は利用側クラスローダのメソッド・インスタンスを強参照するため、
    // キーに WeakHashMap を使ってもクラスローダが GC されずリークする（＝プラグイン再読込ごとに
    // クラスローダが1つ残留する）。ここが対象とする shade 済みプラグイン群でまさに発生するため、
    // キャッシュは持たず毎回解決する。解決コストの大半を占める serializer(Type) はいずれにせよ
    // 呼び出しごとに実行され、loadClass/getMethod はロード済みクラスへのキャッシュヒットで軽微。

    /**
     * 指定した型に対応するシリアライザを解決できるか判定する（検証用）
     *
     * 登録時の検証で使用し、直列化不能な戻り値型・ボディ型を早期に検出する。
     *
     * @param consumerClassLoader 利用側プラグインのクラスローダー
     * @param type 解決対象の Java 型（利用側の `Class` を参照しうる）
     * @return シリアライザを解決できれば true
     */
    fun isSerializable(consumerClassLoader: ClassLoader, type: Type): Boolean =
        resolveSerializerOrNull(consumerClassLoader, type) != null

    /**
     * 値を利用側クラスローダの直列化機構で JSON 文字列にエンコードする
     *
     * @param consumerClassLoader 利用側プラグインのクラスローダー
     * @param type 戻り値の宣言上の Java 型（ジェネリクスを保持する）
     * @param value エンコード対象の値
     * @return JSON 文字列
     */
    fun encodeToString(consumerClassLoader: ClassLoader, type: Type, value: Any): String {
        val handles = Handles.resolve(consumerClassLoader)
        // 利用側のシリアライザを解決（Type ベースなのでクラスローダ非依存）
        val serializer = handles.serializerMethod.invoke(null, type)
        // 利用側の Json.Default で直列化する（Encoder も利用側に揃う）
        return handles.encodeMethod.invoke(handles.jsonDefault, serializer, value) as String
    }

    /**
     * JSON 文字列を利用側クラスローダの直列化機構でデコードする
     *
     * @param consumerClassLoader 利用側プラグインのクラスローダー
     * @param type デコード先の宣言上の Java 型
     * @param jsonText デコード対象の JSON 文字列
     * @return デコードされたオブジェクト
     */
    fun decodeFromString(consumerClassLoader: ClassLoader, type: Type, jsonText: String): Any? {
        val handles = Handles.resolve(consumerClassLoader)
        val serializer = handles.serializerMethod.invoke(null, type)
        return handles.decodeMethod.invoke(handles.jsonDefault, serializer, jsonText)
    }

    /**
     * シリアライザを解決するが、失敗時は例外ではなく null を返す
     *
     * `SerializersKt.serializerOrNull(Type)` を利用側クラスローダで呼び出す。
     *
     * @return 解決できたシリアライザ（利用側の `KSerializer` インスタンス）、失敗時は null
     */
    private fun resolveSerializerOrNull(consumerClassLoader: ClassLoader, type: Type): Any? =
        try {
            Handles.resolve(consumerClassLoader).serializerOrNullMethod.invoke(null, type)
        } catch (e: ReflectiveOperationException) {
            // リフレクション呼び出し自体の失敗（環境不整合）は「解決不能」として扱う
            null
        }

    /**
     * 利用側クラスローダから解決したリフレクションハンドル群
     *
     * @property serializerMethod `SerializersKt.serializer(Type): KSerializer`
     * @property serializerOrNullMethod `SerializersKt.serializerOrNull(Type): KSerializer?`
     * @property jsonDefault `Json.Default` インスタンス
     * @property encodeMethod `Json#encodeToString(SerializationStrategy, Any): String`
     * @property decodeMethod `Json#decodeFromString(DeserializationStrategy, String): Any?`
     */
    private class Handles(
        val serializerMethod: java.lang.reflect.Method,
        val serializerOrNullMethod: java.lang.reflect.Method,
        val jsonDefault: Any,
        val encodeMethod: java.lang.reflect.Method,
        val decodeMethod: java.lang.reflect.Method
    ) {
        companion object {
            /**
             * 利用側クラスローダから必要なクラス・メソッド・フィールドを解決する
             */
            fun resolve(cl: ClassLoader): Handles {
                // シリアライザ解決関数（java.lang.reflect.Type オーバーロード）
                val serializersKt = cl.loadClass("kotlinx.serialization.SerializersKt")
                val serializerMethod = serializersKt.getMethod("serializer", Type::class.java)
                val serializerOrNullMethod = serializersKt.getMethod("serializerOrNull", Type::class.java)

                // 既定の Json インスタンス（利用側クラスローダの Encoder を内包する）
                val jsonClass = cl.loadClass("kotlinx.serialization.json.Json")
                val jsonDefault = jsonClass.getField("Default").get(null)

                // encode/decode は StringFormat インターフェースのメンバー
                val serializationStrategy = cl.loadClass("kotlinx.serialization.SerializationStrategy")
                val deserializationStrategy = cl.loadClass("kotlinx.serialization.DeserializationStrategy")
                val encodeMethod = jsonClass.getMethod("encodeToString", serializationStrategy, Any::class.java)
                val decodeMethod = jsonClass.getMethod("decodeFromString", deserializationStrategy, String::class.java)

                return Handles(
                    serializerMethod = serializerMethod,
                    serializerOrNullMethod = serializerOrNullMethod,
                    jsonDefault = jsonDefault,
                    encodeMethod = encodeMethod,
                    decodeMethod = decodeMethod
                )
            }
        }
    }
}
