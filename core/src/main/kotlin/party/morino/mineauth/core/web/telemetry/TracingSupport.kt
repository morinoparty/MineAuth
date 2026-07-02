package party.morino.mineauth.core.web.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import party.morino.mineauth.core.file.data.DatabaseConfig
import party.morino.mineauth.core.file.data.MineAuthConfig

/**
 * トレーシング用の再利用ヘルパー
 *
 * 生のOpenTelemetry APIをアプリケーションコードに散乱させず、
 * このファイルのヘルパー経由でスパンを作成する。
 * トレーシングが無効の場合はNoOp Tracerが返るため、安全に呼び出せる。
 */

/**
 * 指定した名前のスパンを開始してブロックを実行する
 *
 * 現在のOpenTelemetry Contextを親としてスパンを作成し、
 * コルーチンContextにスパンを載せることでsuspend関数を跨いだ親子付けを実現する。
 * ブロックが例外を投げた場合はスパンに例外を記録し、ステータスをERRORにする。
 *
 * @param name スパン名（識別子を含めないこと。属性で表現する）
 * @param kind スパンの種類（デフォルト: INTERNAL）
 * @param attributes スパン開始時に設定する属性
 * @param tracer 使用するTracer（テスト用に差し替え可能）
 * @param block 実行する処理（スパンを受け取って追加の属性を設定できる）
 * @return ブロックの戻り値
 */
suspend fun <T> withSpan(
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    tracer: Tracer = TelemetryProvider.getTracer(),
    block: suspend (Span) -> T
): T {
    // 現在のContextを親としてスパンを開始
    val span = tracer
        .spanBuilder(name)
        .setSpanKind(kind)
        .setParent(Context.current())
        .setAllAttributes(attributes)
        .startSpan()

    return try {
        // スパンをコルーチンContextに載せて、suspend関数を跨いでも子スパンが正しく紐づくようにする
        withContext(span.storeInContext(Context.current()).asContextElement()) {
            block(span)
        }
    } catch (e: Throwable) {
        // 例外を記録してステータスをERRORに設定
        span.recordException(e)
        span.setStatus(StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}

/**
 * データベースアクセス用のスパンを開始してブロックを実行する
 *
 * スパン名は `db.<テーブル名>.<操作名>` の形式で、semconv準拠のDB属性を設定する。
 *
 * @param table 対象テーブル名（例: oauth_clients）
 * @param operation 操作名（select / insert / update / delete）
 * @param block 実行するDB処理
 * @return ブロックの戻り値
 */
suspend fun <T> withDatabaseSpan(
    table: String,
    operation: String,
    block: suspend (Span) -> T
): T {
    // DB属性を組み立てる（db.system.nameは設定から解決）
    val attributes = Attributes.builder()
        .put(TelemetryAttributes.DB_SYSTEM_NAME, resolveDatabaseSystemName())
        .put(TelemetryAttributes.DB_COLLECTION_NAME, table)
        .put(TelemetryAttributes.DB_OPERATION_NAME, operation)
        .build()

    return withSpan("db.$table.$operation", SpanKind.CLIENT, attributes, block = block)
}

/**
 * 設定からデータベースシステム名を解決する
 *
 * Koinが未初期化・設定未登録の場合（テスト環境など）は "unknown" を返す。
 *
 * @return データベースシステム名（sqlite / mysql / unknown）
 */
private fun resolveDatabaseSystemName(): String {
    return try {
        when (GlobalContext.getOrNull()?.getOrNull<MineAuthConfig>()?.database) {
            is DatabaseConfig.SQLite -> "sqlite"
            is DatabaseConfig.MySQL -> "mysql"
            null -> "unknown"
        }
    } catch (e: Exception) {
        "unknown"
    }
}
