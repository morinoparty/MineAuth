package party.morino.mineauth.addons.betonquest.utils

import org.betonquest.betonquest.quest.objective.variable.VariableObjective

/**
 * BetonQuestのVariableObjectiveに格納されたシリアライズデータを解析するユーティリティ
 * VariableObjectiveは key:value のペアを改行区切りで保存しており、
 * エスケープ処理も含めた正確なデシリアライズを行う
 */
object VariableDataParser {

    /**
     * シリアライズされたVariableObjectiveデータをMap<String, String>に変換する
     *
     * @param serializedData rawObjectivesから取得したシリアライズ済みデータ
     * @return デシリアライズされたキー・バリューマップ（空文字の場合は空マップ）
     */
    fun parse(serializedData: String): Map<String, String> {
        if (serializedData.isBlank()) return emptyMap()
        // BetonQuestの公式デシリアライズメソッドを使用してエスケープ処理を正確に行う
        return VariableObjective.VariableData.deserializeData(serializedData)
    }
}
