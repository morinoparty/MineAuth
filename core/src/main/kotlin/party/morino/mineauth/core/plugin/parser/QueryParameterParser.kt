package party.morino.mineauth.core.plugin.parser

import arrow.core.Either
import arrow.core.right
import io.ktor.server.application.ApplicationCall
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.route.ResolveError

/**
 * クエリパラメータをパースするクラス
 * クエリストリングから全パラメータをMapとして取得する
 */
class QueryParameterParser : ParameterParser<Map<String, String>> {

    override suspend fun parse(
        call: ApplicationCall,
        paramInfo: ParameterInfo
    ): Either<ResolveError, Map<String, String>> {
        // クエリパラメータをMapとして返す
        val queryParams = call.request.queryParameters.entries()
            .associate { it.key to (it.value.firstOrNull() ?: "") }
        return queryParams.right()
    }
}

/**
 * クエリパラメータパーサーのファクトリ
 */
class QueryParameterParserFactory : ParameterParserFactory {
    private val parser = QueryParameterParser()

    override fun supports(paramInfo: ParameterInfo): Boolean {
        return paramInfo is ParameterInfo.QueryParams
    }

    override fun createParser(paramInfo: ParameterInfo): ParameterParser<*>? {
        return if (supports(paramInfo)) parser else null
    }
}
