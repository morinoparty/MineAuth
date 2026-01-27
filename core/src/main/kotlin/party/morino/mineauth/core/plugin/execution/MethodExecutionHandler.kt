package party.morino.mineauth.core.plugin.execution

import arrow.core.Either
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata

/**
 * メソッド実行を担当するハンドラーのインターフェース
 * Cloud (Incendo/cloud) の KotlinMethodCommandExecutionHandler パターンに基づく
 *
 * suspend関数と通常関数で異なる実装を提供することで、
 * クラスローダー互換性の問題を適切に処理する
 */
interface MethodExecutionHandler {
    /**
     * エンドポイントメタデータに基づいてハンドラーメソッドを実行する
     *
     * @param metadata エンドポイントのメタデータ（対象メソッドとハンドラーインスタンスを含む）
     * @param resolvedParams 解決済みのパラメータリスト（メソッド引数の順序で配置）
     * @return 実行結果。成功時はメソッドの戻り値、失敗時はExecutionError
     */
    suspend fun execute(
        metadata: EndpointMetadata,
        resolvedParams: List<Any?>
    ): Either<ExecutionError, Any?>
}
