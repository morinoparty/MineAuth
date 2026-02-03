package party.morino.mineauth.core.plugin.parser

import arrow.core.Either
import arrow.core.raise.either
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.route.ResolveError

/**
 * リクエストボディをパースするクラス
 * JSONボディをデシリアライズして指定された型のオブジェクトに変換する
 */
class BodyParameterParser(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ParameterParser<Any> {

    override suspend fun parse(
        call: ApplicationCall,
        paramInfo: ParameterInfo
    ): Either<ResolveError, Any> = either {
        // Bodyでない場合はエラー（呼び出し側で保証されるべき）
        val bodyParam = paramInfo as? ParameterInfo.Body
            ?: raise(ResolveError.InvalidBodyFormat(IllegalArgumentException("Not a body parameter")))

        try {
            // リクエストボディをテキストとして受信
            val bodyText = call.receiveText()
            // 型に基づいてシリアライザを取得
            val serializer = serializer(bodyParam.type)
            // デシリアライズして返す
            json.decodeFromString(serializer, bodyText) as Any
        } catch (e: Exception) {
            raise(ResolveError.InvalidBodyFormat(e))
        }
    }
}

/**
 * ボディパラメータパーサーのファクトリ
 */
class BodyParameterParserFactory(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ParameterParserFactory {
    private val parser by lazy { BodyParameterParser(json) }

    override fun supports(paramInfo: ParameterInfo): Boolean {
        return paramInfo is ParameterInfo.Body
    }

    override fun createParser(paramInfo: ParameterInfo): ParameterParser<*>? {
        return if (supports(paramInfo)) parser else null
    }
}
