package com.omega.hdha

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BotProtocolTest {
    @Test fun buildsCorrectReadOnlyInquiryCdb() {
        assertArrayEquals(byteArrayOf(0x12, 0, 0, 0, 36, 0), BotProtocol.buildInquiryCdb())
    }

    @Test fun buildsThirtyOneByteCbwWithLittleEndianFieldsAndDataInFlag() {
        val cbw = BotProtocol.buildCbw(0x12345678, 36, BotProtocol.buildInquiryCdb())
        assertEquals(31, cbw.size)
        assertArrayEquals(byteArrayOf(0x55, 0x53, 0x42, 0x43), cbw.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), cbw.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(36, 0, 0, 0), cbw.copyOfRange(8, 12))
        assertEquals(0x80.toByte(), cbw[12])
        assertEquals(6, cbw[14].toInt())
    }

    @Test fun rejectsInvalidCbwArguments() {
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.buildCbw(0, 36, BotProtocol.buildInquiryCdb()) }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.buildCbw(1, -1, BotProtocol.buildInquiryCdb()) }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.buildCbw(1, 36, ByteArray(17)) }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.buildCbw(1, 36, byteArrayOf(0x12), 16) }
    }

    @Test fun parsesCswAndChecksUnsignedLittleEndianFields() {
        val bytes = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(0xFEDCBA98.toInt()).putInt(4).put(0.toByte()).array()
        val parsed = BotProtocol.parseCsw(bytes, expectedTag = 0xFEDCBA98L, expectedTransferLength = 36)
        assertEquals(0xFEDCBA98L, parsed.tag)
        assertEquals(4L, parsed.residue)
        assertEquals(0, parsed.status)
    }

    @Test fun rejectsMalformedCswLengthSignatureTagResidueAndStatus() {
        val valid = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(7).putInt(0).put(0.toByte()).array()
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(ByteArray(12)) }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(ByteArray(13)) }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(valid, expectedTag = 8) }
        val tooMuchResidue = valid.copyOf().apply { this[8] = 37 }
        assertThrows(IllegalArgumentException::class.java) {
            BotProtocol.parseCsw(tooMuchResidue, expectedTag = 7, expectedTransferLength = 36)
        }
        for (status in 1..3) {
            val badStatus = valid.copyOf().apply { this[12] = status.toByte() }
            if (status == 3) assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(badStatus) }
            else assertEquals(status, BotProtocol.parseCsw(badStatus).status)
        }
    }

    @Test fun buildsAtaPassThrough16IdentifyCdbReadOnlyPioDataIn() {
        val cdb = BotProtocol.buildAtaIdentifyDeviceCdb()
        assertEquals(16, cdb.size)
        assertEquals(0x85.toByte(), cdb[0])
        assertEquals(0x09.toByte(), cdb[1]) // EXTEND bit + PIO Data-In protocol
        assertEquals(0x0E.toByte(), cdb[2]) // IN, block units, sector-count transfer length
        assertEquals(1, cdb[6].toInt())
        assertEquals(0xEC.toByte(), cdb[14])
    }

    @Test fun parsesAtaIdentifyStringsAndCapabilityBits() {
        val data = ByteArray(512)
        fun putWord(index: Int, value: Int) {
            data[index * 2] = value.toByte()
            data[index * 2 + 1] = (value ushr 8).toByte()
        }
        fun putAtaString(firstWord: Int, wordCount: Int, value: String) {
            val padded = value.padEnd(wordCount * 2, ' ').take(wordCount * 2)
            for (i in 0 until wordCount) putWord(firstWord + i, (padded[i * 2].code shl 8) or padded[i * 2 + 1].code)
        }
        putWord(0, 0x0040)
        putAtaString(10, 10, "SN123456")
        putAtaString(23, 4, "FW1.2")
        putAtaString(27, 20, "EXAMPLE SATA SSD")
        putWord(82, 1)
        putWord(83, 0x0400)
        putWord(85, 1)
        putWord(49, 1 shl 9)
        putWord(60, 0xFFFF)
        putWord(61, 0x000F)
        putWord(100, 0x5678)
        putWord(101, 0x1234)
        putWord(102, 0)
        putWord(103, 0)
        putWord(217, 1)
        val parsed = BotProtocol.parseAtaIdentify(data)
        assertEquals("SN123456", parsed.serialNumber)
        assertEquals("FW1.2", parsed.firmware)
        assertEquals("EXAMPLE SATA SSD", parsed.model)
        assertTrue(parsed.smartSupported)
        assertTrue(parsed.smartEnabled)
        assertTrue(parsed.lba48Supported)
        assertTrue(parsed.nonRotatingMedia)
        assertNull(parsed.rotationRateRpm)
        assertEquals(0x000F_FFFFL, parsed.lba28SectorCount)
        assertEquals(0x12345678L, parsed.lba48SectorCount)
        assertEquals(0x12345678L * 512L, parsed.capacityBytes)
    }

    @Test fun parsesAtaRotationRateWithoutCallingNonRotatingMediaOneRpm() {
        val data = ByteArray(512)
        data[0] = 0x40
        data[434] = 0x58 // word 217 low byte = 0x0258 = 600 RPM
        data[435] = 0x02
        val parsed = BotProtocol.parseAtaIdentify(data)
        assertFalse(parsed.nonRotatingMedia)
        assertEquals(600, parsed.rotationRateRpm)
    }

    @Test fun rejectsAtaIdentifyWrongLengthAndInvalidGeneralConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(ByteArray(511)) }
        val invalid = ByteArray(512).apply { this[1] = 0x80.toByte() }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(invalid) }
    }

    @Test fun validatesOptionalAtaIdentifyChecksum() {
        val valid = ByteArray(512)
        valid[0] = 0x40
        valid[511] = 0xA5.toByte()
        valid[510] = 0x1B // Sum of all bytes is 256 (mod 256 == 0).
        assertNotNull(BotProtocol.parseAtaIdentify(valid))
        valid[510] = 0x1A
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(valid) }
    }


    @Test fun fallsBackToLba28WhenLba48IsNotAdvertised() {
        val data = ByteArray(512)
        data[0] = 0x40
        fun putWord(index: Int, value: Int) {
            data[index * 2] = value.toByte()
            data[index * 2 + 1] = (value ushr 8).toByte()
        }
        putWord(49, 1 shl 9)
        putWord(60, 0x1234)
        putWord(61, 0x0002)
        putWord(100, 0xFFFF)
        val parsed = BotProtocol.parseAtaIdentify(data)
        assertFalse(parsed.lba48Supported)
        assertEquals(0x0002_1234L, parsed.lba28SectorCount)
        assertEquals(0L, parsed.lba48SectorCount)
        assertEquals(0x0002_1234L * 512L, parsed.capacityBytes)
    }

    @Test fun rejectsZeroAndAllOnesAtaGeneralConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(ByteArray(512)) }
        val allOnes = ByteArray(512).apply { this[0] = 0xFF.toByte(); this[1] = 0xFF.toByte() }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(allOnes) }
    }

    @Test fun noLbaSupportDoesNotInventCapacity() {
        val data = ByteArray(512)
        data[0] = 0x40
        data[120] = 0xFF.toByte()
        data[121] = 0xFF.toByte()
        val parsed = BotProtocol.parseAtaIdentify(data)
        assertEquals(0L, parsed.capacityBytes)
    }

    @Test fun parsesScsiInquiryIdentityFields() {
        val response = ByteArray(36)
        response[1] = 0x80.toByte()
        "SEAGATE ".toByteArray().copyInto(response, 8)
        "Expansion Desk  ".toByteArray().copyInto(response, 16)
        "1234".toByteArray().copyInto(response, 32)
        val parsed = BotProtocol.parseInquiry(response)
        assertEquals("SEAGATE", parsed.vendor)
        assertEquals("Expansion Desk", parsed.product)
        assertEquals("1234", parsed.revision)
        assertTrue(parsed.removable)
        assertEquals(0, parsed.peripheralDeviceType)
    }

    @Test fun rejectsShortScsiInquiryResponse() {
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseInquiry(ByteArray(35)) }
    }
}
