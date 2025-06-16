package party.morino.mineauth.core.web.router.auth.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import java.util.*

@Serializable
data class AuthorizedData(
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val state: String,
    val codeChallenge: String,
    val uniqueId: @Serializable(with = UUIDSerializer::class) UUID
)
