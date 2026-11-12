package party.morino.mineauth.core.mocks.logging

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * 出力されたログを記録するハンドラー
 * ログの内容で管理者への通知を検証するテストで使う
 *
 * 記録はログに書き出される内容と同じ文字列で返す。原因の例外が添付されたレコードは、
 * メッセージに続けてそのスタックトレース（例外のメッセージを含む）を持つため、
 * 例外の全文がログに残ることも、秘密情報がログに残らないことも同じ形で検証できる。
 */
class RecordingLogHandler : Handler() {
    private val records = mutableListOf<LogRecord>()

    // 記録したsevereの出力内容
    val severes: List<String>
        get() = outputsAt(Level.SEVERE)

    // 記録したwarningの出力内容
    val warnings: List<String>
        get() = outputsAt(Level.WARNING)

    override fun publish(record: LogRecord) {
        records.add(record)
    }

    override fun flush() = Unit

    override fun close() = Unit

    /**
     * このハンドラーだけに出力するロガーを作る
     * テストの出力を汚さないよう親ハンドラーには流さない
     */
    fun newLogger(): Logger = Logger.getAnonymousLogger().also {
        it.useParentHandlers = false
        it.addHandler(this)
    }

    /**
     * 指定したレベルのレコードを、ログに書き出される内容の文字列にする
     * 原因の例外がなければメッセージだけになる
     *
     * @param level 取り出すレベル
     * @return 記録した順の出力内容
     */
    private fun outputsAt(level: Level): List<String> =
        records.filter { it.level == level }.map { record ->
            record.thrown?.let { "${record.message}\n${it.stackTraceToString()}" } ?: record.message
        }
}
