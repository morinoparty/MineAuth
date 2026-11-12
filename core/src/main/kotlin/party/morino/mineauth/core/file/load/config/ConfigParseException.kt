package party.morino.mineauth.core.file.load.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import party.morino.mineauth.core.file.data.MineAuthConfig

/**
 * 設定ファイルを解釈できなかったことを表す例外
 *
 * kotlinx.serializationの例外のメッセージは、1行目にエラーの位置（オフセットとJSONパス）を持ち、
 * 2行目以降にその前後の入力（JSON input）を引用する。config.jsonではそこにデータベースのパスワードやpepperが含まれうる。
 * 1行目にも、JSONパスのマップのキー（['…']）や「but had '…'」のように入力の一部が引用される。
 * 値の中の「'」や改行、「 at path: 」で引用の区切りを偽装できるため、入力を取り除くのではなく、
 * 入力を含みえない部分（オフセット、設定の形式に実在するプロパティ名、パーサーの定型文）だけを組み立て直す。
 * 元の例外は原因として添付しない（スタックトレースのCaused byに引用が出るため）。
 * そのため起動時のプラグイン有効化の失敗ログにも、/mineauth reload の報告にも、そのまま出力してよい
 *
 * @param message ファイル名と、入力を含まないエラーの要約
 */
class ConfigParseException private constructor(message: String) : IllegalStateException(message) {
    companion object {
        // 伏せた部分の代わりに置く文字列
        private const val REDACTED = "<redacted>"

        // 構文エラーのメッセージの先頭（オフセットは数字だけなので、そのまま残してよい）
        private val OFFSET_PREFIX = Regex("""^Unexpected JSON token at offset (\d+): """)

        // JSONパスの始まり（列挙型のエラーでは「at path」の後にコロンが無い）
        // 最初の出現を使う。入力に同じ文字列があっても、パスは形式の名前でしか残らず、その前の説明は定型文に一致しない
        private val PATH_START = Regex(""" at path:? (?=\$)""")

        // JSONパスの1区間（プロパティ名か配列の添字）
        private val PATH_SEGMENT = Regex("""\.([A-Za-z_][A-Za-z0-9_]*)|\[\d+]""")

        // パーサーの定型文のうち、構文の記号かEOFしか引用しない説明（「Expected colon ':', but had '}' instead」など）
        private val SYMBOL_ONLY_DESCRIPTION = Regex("""[A-Za-z ,]*(?:'(?:[{}\[\]:,"]|EOF)'[A-Za-z ,]*){0,2}""")

        // 「but had h instead」のように、入力の文字を引用符なしで示す説明
        private val UNQUOTED_INPUT = Regex("""\bhad (?!')""")

        // 必須の項目が欠けている場合のメッセージ（項目名は設定の形式と照合してから残す）
        private val MISSING_FIELDS = Regex("""^Fields? \[?([A-Za-z0-9_, ']+?)]? (?:is|are) required for type with serial name """)

        // 設定の形式に実在するプロパティ名（これと一致する部分は入力ではなく形式に由来する）
        private val schemaNames: Set<String> by lazy {
            mutableSetOf<String>().also { collectElementNames(MineAuthConfig.serializer().descriptor, it, mutableSetOf()) }
        }

        /**
         * パーサーの例外から、入力の引用を取り除いた例外を作る
         *
         * @param fileName 解釈できなかったファイルの名前
         * @param cause パーサーの例外（原因としては添付しない）
         * @return メッセージが入力を含まない要約だけの例外
         */
        fun of(fileName: String, cause: SerializationException): ConfigParseException =
            ConfigParseException("$fileName could not be parsed: ${summaryOf(cause)}")

        /**
         * パーサーの例外のメッセージから、ログや画面に出してよい1行を作る
         *
         * @param cause パーサーの例外
         * @return 入力を含まない要約
         */
        fun summaryOf(cause: SerializationException): String {
            val line = firstLineOf(cause) ?: return cause.javaClass.simpleName
            val pathMatch = PATH_START.find(line)
            val path = pathMatch?.let { " at path: ${safePathOf(line.substring(it.range.last + 1))}" }.orEmpty()
            // パスより前の部分だけを説明として扱う
            val description = pathMatch?.let { line.substring(0, it.range.first) } ?: line
            return describeSafely(description, cause) + path
        }

        /**
         * 説明の部分から、入力を含まない部分だけを残す
         *
         * @param description JSONパスより前の部分
         * @param cause パーサーの例外（定型文に当てはまらない場合に種類を示す）
         * @return 入力を含まない説明
         */
        private fun describeSafely(description: String, cause: SerializationException): String {
            val offset = OFFSET_PREFIX.find(description)
            if (offset != null) {
                val rest = description.substring(offset.range.last + 1)
                val prefix = "Unexpected JSON token at offset ${offset.groupValues[1]}"
                // 記号だけを引用する定型文は構文の誤りの手がかりなので残し、それ以外は入力を引用しうるので省く
                val safe = SYMBOL_ONLY_DESCRIPTION.matches(rest) && !UNQUOTED_INPUT.containsMatchIn(rest)
                return if (safe) "$prefix: $rest" else "$prefix ($REDACTED)"
            }
            val missing = MISSING_FIELDS.find(description)
            if (missing != null) {
                // 欠けている項目名は形式に由来するので、形式に実在する名前だけを残す
                val names = missing.groupValues[1].split(',').map { it.trim().trim('\'') }.filter { it in schemaNames }
                return "Missing required field${if (names.size == 1) "" else "s"}: ${names.joinToString().ifEmpty { REDACTED }}"
            }
            return "${cause.javaClass.simpleName} ($REDACTED)"
        }

        /**
         * JSONパスのうち、設定の形式に実在するプロパティ名と配列の添字だけを残す
         *
         * マップのキーはdatabase.propertiesやエクスポーターのheadersでシークレットを含みうるので伏せ、
         * それ以降は残さない。形式にない名前が現れた場合も、入力に由来しうるのでそこで打ち切る
         *
         * @param rawPath メッセージ中の「$」から行末まで
         * @return 入力を含まないJSONパス
         */
        private fun safePathOf(rawPath: String): String {
            val safe = StringBuilder("$")
            var index = 1
            while (index < rawPath.length) {
                if (rawPath.startsWith("['", index)) {
                    safe.append("['$REDACTED']")
                    break
                }
                val segment = PATH_SEGMENT.find(rawPath, index)?.takeIf { it.range.first == index } ?: break
                val name = segment.groups[1]?.value
                if (name != null && name !in schemaNames) break
                safe.append(segment.value)
                index = segment.range.last + 1
            }
            return safe.toString()
        }

        /**
         * 形式の記述子をたどり、プロパティ名を集める
         *
         * @param descriptor 記述子
         * @param names 集めた名前
         * @param visited たどった記述子（再帰的な型で無限にたどらないため）
         * リストやマップは要素の型が違っても同じシリアル名を持つので、名前ではなく記述子の等価性で判定する
         */
        private fun collectElementNames(descriptor: SerialDescriptor, names: MutableSet<String>, visited: MutableSet<SerialDescriptor>) {
            if (!visited.add(descriptor)) return
            for (index in 0 until descriptor.elementsCount) {
                names += descriptor.getElementName(index)
                collectElementNames(descriptor.getElementDescriptor(index), names, visited)
            }
        }

        /**
         * 例外のメッセージの1行目を取り出す
         *
         * kotlinx.serializationの例外では、1行目が入力をまとめて引用しない部分にあたる。
         * それ以外の例外でも、ログや画面に1行で示すときに使う
         *
         * @param cause 例外
         * @return 1行目（メッセージがないか空白だけの場合はnull）
         */
        fun firstLineOf(cause: Throwable): String? =
            cause.message?.lineSequence()?.first()?.takeIf { it.isNotBlank() }
    }
}
