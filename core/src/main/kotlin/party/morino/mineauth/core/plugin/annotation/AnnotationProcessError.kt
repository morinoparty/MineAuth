package party.morino.mineauth.core.plugin.annotation

import kotlin.reflect.KType

/**
 * アノテーション処理中に発生するエラーを表すsealed class
 */
sealed class AnnotationProcessError {
    /**
     * 無効なパス形式
     * @property path 問題のあるパス
     * @property reason 無効な理由
     */
    data class InvalidPath(val path: String, val reason: String) : AnnotationProcessError()

    /**
     * サポートされていないパラメータ型
     * @property type サポートされていない型
     */
    data class UnsupportedParameterType(val type: KType) : AnnotationProcessError()

    /**
     * 必須アノテーションの欠落
     * @property parameterName アノテーションが欠落しているパラメータ名
     */
    data class MissingAnnotation(val parameterName: String) : AnnotationProcessError()

    /**
     * 競合するアノテーション
     * @property parameterName 複数のアノテーションが付与されたパラメータ名
     */
    data class ConflictingAnnotations(val parameterName: String) : AnnotationProcessError()

    /**
     * HTTPマッピングアノテーションの欠落
     * @property methodName メソッド名
     */
    data class MissingHttpMapping(val methodName: String) : AnnotationProcessError()
}
