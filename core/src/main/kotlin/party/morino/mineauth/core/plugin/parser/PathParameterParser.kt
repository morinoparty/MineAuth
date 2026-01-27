package party.morino.mineauth.core.plugin.parser

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.application.ApplicationCall
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.route.ResolveError
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmErasure

/**
 * パスパラメータをパースするクラス
 * URLパス内の{id}形式のパラメータを取得し、指定された型に変換する
 */
class PathParameterParser : ParameterParser<Any> {

    override suspend fun parse(
        call: ApplicationCall,
        paramInfo: ParameterInfo
    ): Either<ResolveError, Any> = either {
        // PathParamでない場合はエラー（呼び出し側で保証されるべき）
        val pathParam = paramInfo as? ParameterInfo.PathParam
            ?: raise(ResolveError.MissingPathParameter("unknown"))

        val name = pathParam.name
        val targetType = pathParam.type.jvmErasure

        // パスパラメータから値を取得
        val value = call.parameters[name]
        ensure(value != null) {
            ResolveError.MissingPathParameter(name)
        }

        // 型に応じて変換
        convertToType(value, name, targetType).bind()
    }

    /**
     * 文字列を指定された型に変換する
     *
     * @param value 変換する文字列
     * @param paramName パラメータ名（エラーメッセージ用）
     * @param targetType 変換先の型
     * @return 変換された値
     */
    private fun convertToType(
        value: String,
        paramName: String,
        targetType: KClass<*>
    ): Either<ResolveError, Any> = either {
        try {
            when (targetType) {
                String::class -> value
                Int::class -> value.toInt()
                Long::class -> value.toLong()
                Double::class -> value.toDouble()
                Float::class -> value.toFloat()
                Boolean::class -> value.toBoolean()
                UUID::class -> UUID.fromString(value)
                else -> value // デフォルトは文字列として返す
            }
        } catch (e: IllegalArgumentException) {
            raise(
                ResolveError.TypeConversionFailed(
                    parameterName = paramName,
                    expectedType = targetType.simpleName ?: "Unknown",
                    actualValue = value
                )
            )
        }
    }
}

/**
 * パスパラメータパーサーのファクトリ
 */
class PathParameterParserFactory : ParameterParserFactory {
    private val parser = PathParameterParser()

    override fun supports(paramInfo: ParameterInfo): Boolean {
        return paramInfo is ParameterInfo.PathParam
    }

    override fun createParser(paramInfo: ParameterInfo): ParameterParser<*>? {
        return if (supports(paramInfo)) parser else null
    }
}
