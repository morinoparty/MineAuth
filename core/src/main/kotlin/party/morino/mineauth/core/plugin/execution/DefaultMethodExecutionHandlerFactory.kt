package party.morino.mineauth.core.plugin.execution

import party.morino.mineauth.core.plugin.annotation.EndpointMetadata

/**
 * デフォルトのメソッド実行ハンドラーファクトリ
 * Cloud (Incendo/cloud) のファクトリパターンに基づく
 *
 * エンドポイントメタデータのisSuspendingフラグに基づいて
 * 適切なハンドラー（suspend用/通常用）を選択・生成する
 */
class DefaultMethodExecutionHandlerFactory : MethodExecutionHandlerFactory {

    // ハンドラーはステートレスなので、シングルトンとして保持する
    private val suspendHandler = SuspendMethodExecutionHandler()
    private val regularHandler = RegularMethodExecutionHandler()

    override fun createHandler(metadata: EndpointMetadata): MethodExecutionHandler {
        // isSuspendingフラグに基づいて適切なハンドラーを返す
        return if (metadata.isSuspending) {
            suspendHandler
        } else {
            regularHandler
        }
    }
}
