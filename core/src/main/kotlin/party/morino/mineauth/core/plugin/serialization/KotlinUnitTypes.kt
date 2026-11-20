package party.morino.mineauth.core.plugin.serialization

import kotlin.reflect.KClass

/** `kotlin.Unit` の完全修飾名（クラスローダーに依存しない比較に使う） */
private const val KOTLIN_UNIT_NAME = "kotlin.Unit"

/** `kotlin.Nothing` の完全修飾名（クラスローダーに依存しない比較に使う） */
private const val KOTLIN_NOTHING_NAME = "kotlin.Nothing"

/**
 * [KClass] が `kotlin.Unit` かを、クラスローダーに依存せず判定する
 *
 * アドオンはMineAuthとは別のクラスローダーでロードされ、サーバー構成によっては
 * 別のKotlinランタイム（他プラグインのlibraries等）の `kotlin.Unit` を参照する。
 * その場合 `kclass == Unit::class` は同名でも別のClassとなり false になるため、
 * 完全修飾名で比較する。
 */
internal fun KClass<*>.isKotlinUnit(): Boolean = qualifiedName == KOTLIN_UNIT_NAME

/**
 * [KClass] が `kotlin.Unit` または `kotlin.Nothing` かを、クラスローダーに依存せず判定する
 *
 * どちらもレスポンスボディを持たない（直列化しない）戻り値型として扱う。
 */
internal fun KClass<*>.isKotlinUnitOrNothing(): Boolean =
    isKotlinUnit() || qualifiedName == KOTLIN_NOTHING_NAME

/**
 * 値が `kotlin.Unit` のインスタンスかを、クラスローダーに依存せず判定する
 *
 * 別ランタイム由来の `Unit` は `is Unit` で判定できないため、クラス名で比較する。
 */
internal fun Any?.isKotlinUnitValue(): Boolean = this != null && this.javaClass.name == KOTLIN_UNIT_NAME
