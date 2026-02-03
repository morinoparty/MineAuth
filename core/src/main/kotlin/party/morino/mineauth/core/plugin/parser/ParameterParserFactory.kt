package party.morino.mineauth.core.plugin.parser

import party.morino.mineauth.core.plugin.annotation.ParameterInfo

/**
 * パラメータパーサーを生成するファクトリのインターフェース
 * Cloud (Incendo/cloud) の MethodArgumentParserFactory パターンに基づく
 *
 * ParameterInfoの型に基づいて適切なパーサーを生成する
 */
interface ParameterParserFactory {
    /**
     * 指定されたパラメータ情報をサポートしているかどうかを判定する
     *
     * @param paramInfo パラメータ情報
     * @return サポートしている場合true
     */
    fun supports(paramInfo: ParameterInfo): Boolean

    /**
     * パラメータ情報に基づいてパーサーを生成する
     *
     * @param paramInfo パラメータ情報
     * @return 生成されたパーサー。サポートしていない場合はnull
     */
    fun createParser(paramInfo: ParameterInfo): ParameterParser<*>?
}
