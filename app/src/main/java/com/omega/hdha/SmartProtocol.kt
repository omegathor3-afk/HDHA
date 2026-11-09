package com.omega.hdha

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SAT/ATA SMART command encoders and conservative parsers. Raw vendor values stay uninterpreted. */
object SmartProtocol {
    const val SECTOR_LENGTH = 512
    const val SMART_COMMAND = 0xB0
    const val SMART_READ_DATA = 0xD0
    const val SMART_READ_THRESHOLDS = 0xD1
    const val SMART_RETURN_STATUS = 0xDA
    const val REQUEST_SENSE_ALLOCATION_LENGTH = 96

    data class Attribute(
        val id: Int,
        val flags: Int,
        val current: Int,
        val worst: Int,
        /** Six ATA bytes in wire order; deliberately not decoded as a universal integer. */
        val rawValueHex: String
    )

    data class SmartData(val revision: Int, val attributes: List<Attribute>)
    data class SmartThreshold(val id: Int, val threshold: Int)

    data class AtaReturnStatus(
        val error: Int,
        val status: Int,
        val lbaMid: Int,
        val lbaHigh: Int,
        /** null means the ATA signature is unknown; it must not be called healthy. */
        val thresholdExceeded: Boolean?
    )

    fun buildReadDataCdb(): ByteArray = buildSmartCdb(SMART_READ_DATA, dataIn = true, ckCond = false)
    fun buildReadThresholdsCdb(): ByteArray = buildSmartCdb(SMART_READ_THRESHOLDS, dataIn = true, ckCond = false)
    fun buildReturnStatusCdb(): ByteArray = buildSmartCdb(SMART_RETURN_STATUS, dataIn = false, ckCond = true)

    fun buildRequestSenseCdb(): ByteArray = byteArrayOf(
        0x03, 1, 0, 0, REQUEST_SENSE_ALLOCATION_LENGTH.toByte(), 0 // DESC=1 requests descriptor-format sense
    )

    private fun buildSmartCdb(feature: Int, dataIn: Boolean, ckCond: Boolean): ByteArray {
        require(feature in setOf(SMART_READ_DATA, SMART_READ_THRESHOLDS, SMART_RETURN_STATUS))
        val hasData = dataIn
        val cdb = ByteArray(16)
        cdb[0] = 0x85.toByte() // ATA PASS-THROUGH(16)
        cdb[1] = if (hasData) 0x09 else 0x07 // EXTEND=1; PIO Data-In or Non-Data protocol
        cdb[2] = when {
            ckCond -> 0x20 // CK_COND=1; no ATA data transfer
            hasData -> 0x0E // T_DIR=IN, BYT_BLOK=1, T_LENGTH=sector count
            else -> 0
        }.toByte()
        cdb[3] = 0 // FEATURES high
        cdb[4] = feature.toByte()
        cdb[5] = 0 // SECTOR_COUNT high
        cdb[6] = if (hasData) 1 else 0
        cdb[7] = 0 // LBA low high
        cdb[8] = 0x4F // SMART signature, LBA low
        cdb[9] = 0 // LBA mid high
        cdb[10] = 0xC2.toByte() // SMART signature, LBA mid
        cdb[11] = 0 // LBA high high
        cdb[12] = 0 // LBA high
        cdb[13] = 0xA0.toByte() // Device: master
        cdb[14] = SMART_COMMAND.toByte()
        cdb[15] = 0
        return cdb
    }

    fun parseReadData(bytes: ByteArray): SmartData {
        validateSector(bytes, "SMART READ DATA")
        val revision = le16(bytes, 0)
        val attributes = ArrayList<Attribute>(30)
        val seen = HashSet<Int>()
        for (index in 0 until 30) {
            val offset = 2 + index * 12
            val id = u8(bytes[offset])
            if (id == 0) continue
            require(seen.add(id)) { "SMART READ DATA contains duplicate attribute ID $id" }
            val flags = u8(bytes[offset + 1]) or (u8(bytes[offset + 2]) shl 8)
            val raw = bytes.copyOfRange(offset + 5, offset + 11)
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            attributes += Attribute(
                id = id,
                flags = flags,
                current = u8(bytes[offset + 3]),
                worst = u8(bytes[offset + 4]),
                rawValueHex = raw
            )
        }
        return SmartData(revision, attributes)
    }

    fun parseReadThresholds(bytes: ByteArray): Map<Int, Int> {
        validateSector(bytes, "SMART READ THRESHOLDS")
        val thresholds = LinkedHashMap<Int, Int>()
        for (index in 0 until 30) {
            val offset = 2 + index * 12
            val id = u8(bytes[offset])
            if (id == 0) continue
            require(!thresholds.containsKey(id)) { "SMART thresholds contain duplicate attribute ID $id" }
            thresholds[id] = u8(bytes[offset + 1])
        }
        return thresholds
    }

    fun parseAtaReturnDescriptor(sense: ByteArray): AtaReturnStatus {
        require(sense.size >= 8) { "REQUEST SENSE response is shorter than its header" }
        val responseCode = u8(sense[0]) and 0x7F
        require(responseCode == 0x72 || responseCode == 0x73) {
            "REQUEST SENSE did not return descriptor format (0x72/0x73)"
        }
        val descriptorEnd = 8 + u8(sense[7])
        require(descriptorEnd <= sense.size) { "REQUEST SENSE additional length exceeds received bytes" }
        var offset = 8
        while (offset + 2 <= descriptorEnd) {
            val code = u8(sense[offset])
            val length = u8(sense[offset + 1])
            val next = offset + 2 + length
            require(next <= descriptorEnd) { "REQUEST SENSE descriptor is truncated" }
            if (code == 0x09) {
                require(length == 0x0C) { "ATA Return Descriptor must contain 12 payload bytes" }
                val error = u8(sense[offset + 3])
                val lbaMid = u8(sense[offset + 9])
                val lbaHigh = u8(sense[offset + 11])
                val thresholdExceeded = when {
                    lbaMid == 0x4F && lbaHigh == 0xC2 -> false
                    lbaMid == 0xF4 && lbaHigh == 0x2C -> true
                    else -> null
                }
                return AtaReturnStatus(
                    error = error,
                    status = u8(sense[offset + 13]),
                    lbaMid = lbaMid,
                    lbaHigh = lbaHigh,
                    thresholdExceeded = thresholdExceeded
                )
            }
            offset = next
        }
        throw IllegalArgumentException("ATA Return Descriptor (0x09) is missing from REQUEST SENSE")
    }

    private fun validateSector(bytes: ByteArray, label: String) {
        require(bytes.size == SECTOR_LENGTH) { "$label must be exactly 512 bytes" }
        val sum = bytes.sumOf { u8(it) } and 0xFF
        require(sum == 0) { "$label checksum is invalid" }
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}
