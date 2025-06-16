package party.morino.mineauth.api.model.common

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import java.util.*

@Serializable
data class ProfileData(
    val username: String, val id: @Serializable(with = UUIDSerializer::class) UUID
)
