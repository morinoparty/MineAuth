package party.morino.mineauth.core.plugin.parser

import arrow.core.Either
import io.ktor.server.application.ApplicationCall
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.route.ResolveError

/**
 * パラメータをパースするインターフェース
 * Cloud (Incendo/cloud) の MethodArgumentParser パターンに基づく
 *
 * 各パラメータタイプ（パス、クエリ、ボディ、認証ユーザー等）に対応した
 * 専用のパーサーを実装することで、責務を明確に分離する
 *
 * @param T パース結果の型
 */
interface ParameterParser<T> {
    /**
     * Ktorのコンテキストからパラメータ値をパースする
     *
     * @param call Ktorの ApplicationCall
     * @param paramInfo パラメータ情報（ParameterInfoのサブクラス）
     * @return パース結果。成功時は解決された値、失敗時はResolveError
     */
    suspend fun parse(
        call: ApplicationCall,
        paramInfo: ParameterInfo
    ): Either<ResolveError, T>
}
