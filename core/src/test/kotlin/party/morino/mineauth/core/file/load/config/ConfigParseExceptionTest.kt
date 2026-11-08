package party.morino.mineauth.core.file.load.config

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * パーサーのメッセージから入力を含まない要約を作る処理のテスト
 * パーサーの文言の変化に左右されないよう、実際のメッセージと同じ形の例外を組み立てて確認する
 */
class ConfigParseExceptionTest {
    private fun summaryOf(message: String) = ConfigParseException.summaryOf(SerializationException("$message\nJSON input: ..."))

    @Test
    @DisplayName("Never keeps input even when it imitates quotes or the path")
    fun redactsInput() {
        val messages = listOf(
            // 1文字の引用に見える値
            "Unexpected JSON token at offset 42: Expected valid boolean literal prefix, but had 'a'Zq9Secret'b' at path: \$.observability.enabled",
            // パスの区切りを含むマップのキー
            "Unexpected JSON token at offset 85: Expected colon ':', but had '}' instead at path: \$.database.properties['pw at path: Zq9Secret']",
            // 値の中の改行で1行目が途中で切れ、偽のパスで終わる
            "Unexpected JSON token at offset 42: Expected valid boolean literal prefix, but had 'x at path: \$.Zq9Secret",
            // 入力の文字を引用符なしで示す説明と、1文字の入力の引用
            "Unexpected JSON token at offset 9: Expected EOF after parsing, but had Z instead at path: \$",
            "Unexpected JSON token at offset 9: Unexpected symbol 'Z' in numeric literal at path: \$.server.port",
            // コロンの無いパスで終わる列挙型のエラー
            "OtlpExporterProtocol does not contain element with name 'Zq9Secret' at path \$.observability.exporters[0].protocol"
        )

        messages.forEach { message ->
            val summary = summaryOf(message)
            assertFalse("Zq9" in summary || "Z " in summary || "'Z'" in summary, summary)
        }
        assertContains(summaryOf(messages[1]), "offset 85")
        assertContains(summaryOf(messages[1]), "at path: \$.database.properties['<redacted>']")
        assertContains(summaryOf(messages[5]), "at path: \$.observability.exporters[0].protocol")
    }
}
