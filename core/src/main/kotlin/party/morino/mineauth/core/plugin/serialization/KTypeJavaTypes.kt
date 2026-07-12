package party.morino.mineauth.core.plugin.serialization

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

/**
 * [KType] をシリアライザ解決に使える [java.lang.reflect.Type] へ変換する
 *
 * kotlin-reflect の [javaType] は「KType の由来する JVM 宣言のシグネチャ」をそのまま返す。
 * suspend 関数は CPS 変換により JVM 上の戻り値型が `Object` になるため、suspend ハンドラーの
 * 戻り値型に [javaType] を使うと `Object.class` が返り、シリアライザ解決が必ず失敗する
 * （登録時は ReturnTypeNotSerializable、実行時は直列化エラー）。
 *
 * そこで、[javaType] が `Object` へ縮退している場合（classifier が `Any` でないのに
 * `Object.class` が返る場合）に限り、classifier（KClass）と型引数から Type を構造的に
 * 再構築する。縮退していなければ宣言由来の [javaType] をそのまま使う（配列・ワイルドカード等の
 * 表現は宣言由来の方が正確なため）。
 */
internal fun KType.toResolvableJavaType(): Type {
    val declared = javaType
    // suspend の CPS 変換による縮退のみを補正する。KType 自体が Any ならそれが正しい。
    if (declared != java.lang.Object::class.java || classifier == Any::class) return declared
    return structuralJavaType()
}

/**
 * classifier と型引数から [Type] を組み立てる（宣言シグネチャに依存しない）
 *
 * 型パラメータ・スター射影は `Object` として扱う（シリアライザ解決不能として検出される）。
 */
private fun KType.structuralJavaType(): Type {
    val raw = (classifier as? KClass<*>)?.java ?: return java.lang.Object::class.java
    if (arguments.isEmpty()) return raw
    val args = arguments.map { it.type?.structuralJavaType() ?: java.lang.Object::class.java }.toTypedArray()
    return SyntheticParameterizedType(raw, args)
}

/**
 * [structuralJavaType] が生成する最小限の [ParameterizedType] 実装
 *
 * kotlinx.serialization の `serializer(Type)` は rawType / actualTypeArguments のみ参照する。
 */
private class SyntheticParameterizedType(
    private val raw: Class<*>,
    private val args: Array<Type>
) : ParameterizedType {
    override fun getRawType(): Type = raw
    override fun getActualTypeArguments(): Array<Type> = args
    override fun getOwnerType(): Type? = null
    override fun toString(): String = "${raw.typeName}<${args.joinToString(", ") { it.typeName }}>"
}
