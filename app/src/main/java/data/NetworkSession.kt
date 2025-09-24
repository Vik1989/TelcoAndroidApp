package data

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi
@Serializable
data class NetworkSession(
    val timestamp: Long,
    val networkName: String,
    val downloadSpeedMbps: Double?,
    val carriers: List<RfAndCaData>
)