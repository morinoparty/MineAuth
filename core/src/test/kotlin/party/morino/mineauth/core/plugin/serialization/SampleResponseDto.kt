package party.morino.mineauth.core.plugin.serialization

import kotlinx.serialization.Serializable

/**
 * クラスローダ分裂の再現テスト用の DTO
 *
 * 利用側プラグインが自前で同梱する `@Serializable` DTO を模したもの。
 * テストでは分離クラスローダーにこのクラスを再ロードさせることで、
 * 生成シリアライザが「別クラスローダの `KSerializer`」を実装する状況を作り出す。
 */
@Serializable
data class SampleResponseDto(
    val name: String,
    val count: Int
)
