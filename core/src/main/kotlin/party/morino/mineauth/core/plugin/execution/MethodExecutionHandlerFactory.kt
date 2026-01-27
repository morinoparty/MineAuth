package party.morino.mineauth.core.plugin.execution

import party.morino.mineauth.core.plugin.annotation.EndpointMetadata

/**
 * メソッド実行ハンドラーを生成するファクトリのインターフェース
 * Cloud (Incendo/cloud) のファクトリパターンに基づく
 *
 * エンドポイントメタデータに基づいて適切なハンドラーを選択・生成する
 * （例：suspend関数用、通常関数用）
 */
interface MethodExecutionHandlerFactory {
    /**
     * エンドポイントメタデータに基づいて適切なハンドラーを生成する
     *
     * @param metadata エンドポイントのメタデータ
     * @return 生成されたハンドラー
     */
    fun createHandler(metadata: EndpointMetadata): MethodExecutionHandler
}
