package party.morino.mineauth.api.utils

import kotlinx.serialization.json.Json

/**
 * 共通のJSON設定オブジェクト
 * 不明なキーを無視し、デフォルト値をエンコード、寛容な解析、見やすい形式で出力する
 */
val json = Json {
    ignoreUnknownKeys = true // 不明なキーを無視する
    encodeDefaults = true // デフォルト値をエンコードする
    isLenient = true // 寛容な解析を行う
    prettyPrint = true // 見やすい形式で出力する
}