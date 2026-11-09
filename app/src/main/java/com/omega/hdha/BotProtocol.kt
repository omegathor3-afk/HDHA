package com.omega.hdha

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** Pure BOT/SCSI codecs. No device I/O is performed here. */
object BotProtocol {
    const val CBW_LENGTH = 31
    const val CSW_LENGTH = 13
    const val INQUIRY_DATA_LENGTH = 36
    private const val CBW_SIGNATURE = 0x43425355
    private const val CSW_SIGNATURE = 0x53425355

    data class CommandStatusWrapper(val tag: Long, val residue: Long, val status: Int)

    data class AtaIdentifyData(val serialNumber: String, val firmware: String, val model: String, val smartSupported: Boolean, val smartEnabled: Boolean, val lba48Supported: Boolean, val rotationRateRpm: Int?)

    data class InquiryData(
        val peripheralDeviceType: Int,
        val removable: Boolean,
        val vendor: String,
        val product: String,
        val revision: String
    )

    fun buildInquiryCdb(): ByteArray = byteArrayOf(
        0x12, 0x00, 0x00, 0x00, INQUIRY_DATA_LENGTH.toByte(), 0x00
    )

    fun buildCbw(tag: Long, transferLength: Long, cdb: ByteArray, lun: Int = 0): ByteArray {
        require(tag in 1L..0xFFFF_FFFFL) { "BOT tag must be a non-zero unsigned 32-bit value" }
        require(transferLength in 0L..0xFFFF_FFFFL) { "Transfer length is out of range" }
        require(lun in 0..15) { "BOT LUN must be between 0 and 15" }
        require(cdb.isNotEmpty() && cdb.size <= 16) { "CDB length must be between 1 and 16 bytes" }

        return ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(tag.toInt())
            putInt(transferLength.toInt())
            put(0x80.toByte()) // Data-In
            put(lun.toByte())
            put(cdb.size.toByte())
            put(cdb)
            repeat(16 - cdb.size) { put(0.toByte()) }
        }.array()
    }


    /** ATA PASS-THROUGH(16), PIO data-in, one 512-byte IDENTIFY DEVICE block. */
    fun buildAtaIdentifyDeviceCdb(): ByteArray = ByteArray(16).apply {
        this[0] = 0x85.toByte()
        this[1] = 0x88.toByte()
        this[2] = 0x0E.toByte()
        this[6] = 1
        this[14] = 0xEC.toByte()
    }

    fun parseAtaIdentify(bytes: ByteArray): AtaIdentifyData {
        require(bytes.size == 512) { "ATA IDENTIFY response must be exactly 512 bytes" }
        fun word(index: Int): Int {
            val offset = index * 2
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }
        require((word(0) and 0x8000) == 0) { "ATA IDENTIFY has an invalid general configuration word" }
        if ((word(255) ushr 8) == 0xA5) {
            require((bytes.sumOf { it.toInt() and 0xFF } and 0xFF) == 0) { "ATA IDENTIFY checksum is invalid" }
        }
        fun ataString(firstWord: Int, wordCount: Int): String {
            val chars = StringBuilder(wordCount * 2)
            for (i in firstWord until firstWord + wordCount) {
                val value = word(i)
                chars.append(((value ushr 8) and 0xFF).toChar())
                chars.append((value and 0xFF).toChar())
            }
            return chars.toString().trim { it <= ' ' || it == '\u0000' }
        }
        val rotation = word(217).let { if (it in 1..0xFFFE) it else null }
        return AtaIdentifyData(
            serialNumber = ataString(10, 10),
            firmware = ataString(23, 4),
            model = ataString(27, 20),
            smartSupported = (word(82) and 1) != 0,
            smartEnabled = (word(85) and 1) != 0,
            lba48Supported = (word(83) and 0x0400) != 0,
            rotationRateRpm = rotation
        )
    }

    fun parseCsw(bytes: ByteArray, expectedTag: Long? = null, expectedTransferLength: Long? = null): CommandStatusWrapper {
        require(bytes.size == CSW_LENGTH) { "CSW must be exactly 13 bytes" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == CSW_SIGNATURE) { "Invalid CSW signature" }
        val tag = buffer.int.toLong() and 0xFFFF_FFFFL
        val residue = buffer.int.toLong() and 0xFFFF_FFFFL
        expectedTag?.let { require(tag == it) { "CSW tag does not match the command" } }
        expectedTransferLength?.let {
            require(it in 0L..0xFFFF_FFFFL) { "Expected transfer length is out of range" }
            require(residue <= it) { "CSW residue exceeds requested transfer length" }
        }
        val status = buffer.get().toInt() and 0xFF
        require(status in 0..2) { "Invalid BOT command status: $status" }
        return CommandStatusWrapper(tag, residue, status)
    }

    fun parseInquiry(bytes: ByteArray): InquiryData {
        require(bytes.size >= INQUIRY_DATA_LENGTH) { "INQUIRY response is shorter than 36 bytes" }
        fun field(start: Int, length: Int): String =
            String(bytes, start, length, Charset.forName("US-ASCII"))
                .trim { it <= ' ' || it == '\u0000' }

        return InquiryData(
            peripheralDeviceType = bytes[0].toInt() and 0x1F,
            removable = (bytes[1].toInt() and 0x80) != 0,
            vendor = field(8, 8),
            product = field(16, 16),
            revision = field(32, 4)
        )
    }
}
