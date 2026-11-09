package com.omega.hdha

/**
 * Describes the transport advertised by a USB Mass Storage interface.
 * This is descriptor-based capability discovery only; it does not prove
 * that a bridge accepts SCSI commands or ATA PASS-THROUGH.
 */
enum class MassStorageTransport {
    BULK_ONLY,
    UAS,
    CBI_OR_CBB,
    UNKNOWN
}

data class MassStorageProfile(
    val transport: MassStorageTransport,
    val hasBulkIn: Boolean,
    val hasBulkOut: Boolean,
    val canAttemptBulkOnlyTransport: Boolean,
    val limitation: String
)

object UsbMassStorageProfile {
    const val CLASS_MASS_STORAGE = 0x08
    const val SUBCLASS_SCSI_TRANSPARENT = 0x06
    const val PROTOCOL_CBI = 0x00
    const val PROTOCOL_CBI_NO_INTERRUPT = 0x01
    const val PROTOCOL_BULK_ONLY = 0x50
    const val PROTOCOL_UAS = 0x62

    fun inspect(info: UsbInterfaceInfo): MassStorageProfile? {
        if (info.interfaceClass != CLASS_MASS_STORAGE) return null

        val bulkIn = info.endpoints.any { it.type == "BULK" && it.direction == "IN" }
        val bulkOut = info.endpoints.any { it.type == "BULK" && it.direction == "OUT" }
        val transport = when (info.protocol) {
            PROTOCOL_BULK_ONLY -> MassStorageTransport.BULK_ONLY
            PROTOCOL_UAS -> MassStorageTransport.UAS
            PROTOCOL_CBI, PROTOCOL_CBI_NO_INTERRUPT -> MassStorageTransport.CBI_OR_CBB
            else -> MassStorageTransport.UNKNOWN
        }
        val scsiTransparent = info.subclass == SUBCLASS_SCSI_TRANSPARENT
        val canAttemptBot = transport == MassStorageTransport.BULK_ONLY &&
            scsiTransparent && bulkIn && bulkOut

        val limitation = when {
            transport == MassStorageTransport.UAS ->
                "UASP advertised; this app does not implement UASP yet."
            transport == MassStorageTransport.CBI_OR_CBB ->
                "CBI/CBB advertised; not supported by the initial transport."
            transport == MassStorageTransport.UNKNOWN ->
                "Unknown mass-storage protocol; transport cannot be selected safely."
            !scsiTransparent ->
                "The interface does not advertise SCSI transparent command set."
            !bulkIn || !bulkOut ->
                "Required Bulk IN and Bulk OUT endpoints were not both found."
            else ->
                "Bulk-Only Transport descriptors look suitable; command support is not yet verified."
        }

        return MassStorageProfile(transport, bulkIn, bulkOut, canAttemptBot, limitation)
    }
}
