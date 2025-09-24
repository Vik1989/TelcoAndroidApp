package data;

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi
@Serializable
data class RfAndCaData(
        val type: String, // e.g., "LTE", "NR", "GSM"
        val status: String, // e.g., "PCell", "SCell"
        val band: String,
        val pci: Int?,          // Physical Cell ID (LTE/NR)
        val tac: Int?,          // Tracking Area Code (LTE/NR)
        val ci: Long?,          // Cell Identity (LTE uses Int for CI, NR uses Long for NCI)
        val rsrp: Int?,
        val rsrq: Int?,
        val sinr: Int?,
        val rssi: Int?
)
