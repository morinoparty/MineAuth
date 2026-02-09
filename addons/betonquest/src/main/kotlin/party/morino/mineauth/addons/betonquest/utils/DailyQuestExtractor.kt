package party.morino.mineauth.addons.betonquest.utils

import party.morino.mineauth.addons.betonquest.data.DailyQuestInfo

/**
 * BetonQuestのrawObjectivesからデイリークエスト情報を抽出するクラス
 *
 * InternalManagerのvariable objectiveに登録された3つのクエストパッケージを読み取り、
 * 各クエストのState（完了状態）とDisplayStorage（表示情報）を解析して
 * DailyQuestInfoのリストを生成する
 */
object DailyQuestExtractor {

    // InternalManagerのobjective IDサフィックス
    private const val INTERNAL_MANAGER_SUFFIX = ".InternalManager"
    // 各クエストのState objective IDサフィックス
    private const val STATE_SUFFIX = ".State"
    // 各クエストのDisplayStorage objective IDサフィックス
    private const val DISPLAY_STORAGE_SUFFIX = ".DisplayStorage"
    // InternalManagerに格納されるクエストパッケージのキープレフィックス
    private const val QUEST_PACKAGE_KEY_PREFIX = "quest_package#"
    // デイリークエスト1日あたりの最大数
    private const val MAX_DAILY_QUESTS = 3

    // クエストパッケージ名に含まれる難易度識別子のマッピング
    private val DIFFICULTY_PATTERNS = mapOf(
        "-Easy-" to "easy",
        "-Normal-" to "normal",
        "-Hard-" to "hard",
        "-Event-" to "event"
    )

    /**
     * rawObjectivesからデイリークエスト情報を抽出する
     *
     * @param rawObjectives PlayerData.getRawObjectives()から取得したマップ
     * @return デイリークエスト情報のリスト（クエスト未登録の場合は空リスト）
     */
    fun extract(rawObjectives: Map<String, String>): List<DailyQuestInfo> {
        // InternalManager objectiveを検索（デイリークエストの登録管理を行うobjective）
        val managerEntry = rawObjectives.entries.find {
            it.key.endsWith(INTERNAL_MANAGER_SUFFIX)
        } ?: return emptyList()

        // InternalManagerのデータをパースしてクエストパッケージ名を取得
        val managerData = VariableDataParser.parse(managerEntry.value)

        // quest_package#0, #1, #2 から登録済みクエストパッケージ名を取得
        val questPackages = (0 until MAX_DAILY_QUESTS).mapNotNull { index ->
            managerData["$QUEST_PACKAGE_KEY_PREFIX$index"]
        }

        // 各クエストパッケージからDailyQuestInfoを生成
        return questPackages.mapNotNull { questPackage ->
            buildDailyQuestInfo(questPackage, rawObjectives)
        }
    }

    /**
     * 個別のクエストパッケージからDailyQuestInfoを組み立てる
     */
    private fun buildDailyQuestInfo(
        questPackage: String,
        rawObjectives: Map<String, String>
    ): DailyQuestInfo? {
        // Stateデータの取得（task_completed, quest_completedの状態）
        val stateData = rawObjectives["$questPackage$STATE_SUFFIX"]
            ?.let { VariableDataParser.parse(it) }
            ?: emptyMap()

        // DisplayStorageデータの取得（タイトル、説明、進捗情報）
        val displayData = rawObjectives["$questPackage$DISPLAY_STORAGE_SUFFIX"]
            ?.let { VariableDataParser.parse(it) }
            ?: emptyMap()

        // パッケージ名から難易度を判定
        val difficulty = parseDifficulty(questPackage)

        // パッケージ名からクエストIDを抽出
        val questId = parseQuestId(questPackage)

        return DailyQuestInfo(
            questId = questId,
            questPackage = questPackage,
            difficulty = difficulty,
            title = displayData["quest_title"] ?: "",
            description = displayData["quest_description"] ?: "",
            taskCompleted = stateData["task_completed"] == "true",
            questCompleted = stateData["quest_completed"] == "true",
            progressPercentage = displayData["progress_percentage"]?.toDoubleOrNull()?.toInt() ?: 0
        )
    }

    /**
     * クエストパッケージ名から難易度を判定する
     * パッケージ名の形式: "{root}-DailyQuest-Quests-{Difficulty}-{questName}"
     */
    private fun parseDifficulty(questPackage: String): String {
        return DIFFICULTY_PATTERNS.entries
            .firstOrNull { questPackage.contains(it.key) }
            ?.value
            ?: "unknown"
    }

    /**
     * クエストパッケージ名からクエストIDを抽出する
     * パッケージ名の形式: "{root}-DailyQuest-Quests-{Difficulty}-{questName}"
     * "-Quests-{Difficulty}-" の後の部分がクエストID
     */
    private fun parseQuestId(questPackage: String): String {
        val questsPrefix = "-Quests-"
        val questsIndex = questPackage.indexOf(questsPrefix)
        if (questsIndex < 0) return questPackage

        // "-Quests-{Difficulty}-" の後の部分を取得
        val afterQuests = questPackage.substring(questsIndex + questsPrefix.length)
        // 難易度部分をスキップして、クエスト名を取得
        val dashIndex = afterQuests.indexOf('-')
        return if (dashIndex >= 0) afterQuests.substring(dashIndex + 1) else afterQuests
    }
}
