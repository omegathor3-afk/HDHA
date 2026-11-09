package com.omega.hdha

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BotProtocolTest {
    @Test fun buildsCorrectReadOnlyInquiryCdb() {
        assertArrayEquals(byteArrayOf(0x12, 0, 0, 0, 36, 0), BotProtocol.buildInquiryCdb())
    }

    @Test fun buildsThirtyOneByteCbwWithLittleEndianSignatureAndDataInFlag() {
        val cbw = BotProtocol.buildCbw(0x12345678, 36, BotProtocol.buildInquiryCdb())
        assertEquals(31, cbw.size)
        assertArrayEquals(byteArrayOf(0x55, 0x53, 0x42, 0x43), cbw.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), cbw.copyOfRange(4, 8))
        assertEquals(0x80.toByte(), cbw[12])
        assertEquals(0, cbw[13].toInt())
        assertEquals(6, cbw[14].toInt())
        assertArrayEquals(BotProtocol.buildInquiryCdb(), cbw.copyOfRange(15, 21))
    }

    @Test fun rejectsInvalidCbwArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            BotProtocol.buildCbw(0, 36, BotProtocol.buildInquiryCdb())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BotProtocol.buildCbw(1, 36, ByteArray(17))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BotProtocol.buildCbw(1, -1, BotProtocol.buildInquiryCdb())
        }
    }

    @Test fun parsesValidCswAsUnsignedLittleEndianValues() {
        val bytes = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(0xFEDCBA98.toInt()).putInt(4).put(0.toByte()).array()
        val csw = BotProtocol.parseCsw(bytes)
        assertEquals(0xFEDCBA98L, csw.tag)
        assertEquals(4L, csw.residue)
        assertEquals(0, csw.status)
    }

    @Test fun rejectsWrongCswLengthSignatureAndStatus() {
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(ByteArray(12)) }
        val wrongSignature = ByteArray(13)
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(wrongSignature) }
        val invalidStatus = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(1).putInt(0).put(3.toByte()).array()
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(invalidStatus) }
    }

    @Test fun rejectsCswWithMismatchedExpectedTag() {
        val bytes = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(7).putInt(0).put(0.toByte()).array()
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseCsw(bytes, expectedTag = 8) }
    }

    @Test fun rejectsCswResidueLargerThanRequestedTransfer() {
        val bytes = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(7).putInt(37).put(0.toByte()).array()
        assertThrows(IllegalArgumentException::class.java) {
            BotProtocol.parseCsw(bytes, expectedTag = 7, expectedTransferLength = 36)
        }
    }

    @Test fun acceptsZeroResidueForExactInquiryLength() {
        val bytes = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(7).putInt(0).put(0.toByte()).array()
        assertEquals(0L, BotProtocol.parseCsw(bytes, 7, 36).residue)
    }

    @Test fun buildsAtaPassThrough16IdentifyCdbAsPioDataIn() {
        val cdb = BotProtocol.buildAtaIdentifyDeviceCdb()
        assertEquals(16, cdb.size)
        assertEquals(0x85.toByte(), cdb[0])
        assertEquals(0x09.toByte(), cdb[1])
        assertEquals(0x0E.toByte(), cdb[2])
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
        putWord(217, 1)
        val parsed = BotProtocol.parseAtaIdentify(data)
        assertEquals("SN123456", parsed.serialNumber)
        assertEquals("FW1.2", parsed.firmware)
        assertEquals("EXAMPLE SATA SSD", parsed.model)
        assertTrue(parsed.smartSupported)
        assertTrue(parsed.smartEnabled)
        assertTrue(parsed.lba48Supported)
        assertEquals(1, parsed.rotationRateRpm)
    }

    @Test fun rejectsAtaIdentifyWrongLengthAndInvalidGeneralConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(ByteArray(511)) }
        val invalid = ByteArray(512)
        invalid[1] = 0x80.toByte()
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(invalid) }
    }

    @Test fun validatesAtaIdentifyChecksumWhenSignatureIsPresent() {
        val valid = ByteArray(512)
        valid[511] = 0xA5.toByte()
        valid[510] = 0x5B
        assertEquals("", BotProtocol.parseAtaIdentify(valid).model)
        valid[510] = 0x5A
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(valid) }
    }


    @Test fun buildsSatAtaPassThrough12IdentifyCdb() {
        assertArrayEquals(
            byteArrayOf(0xA1.toByte(), 0x08, 0x00, 0x0E, 0x00, 0x01, 0, 0, 0, 0xEC.toByte(), 0, 0),
            BotProtocol.buildAtaPassThrough12IdentifyCdb()
        )
    }

    @Test fun parsesAtaIdentifyStringsAndLba48Capacity() {
        val identify = ByteArray(512)
        fun putWord(index: Int, value: Int) {
            identify[index * 2] = value.toByte()
            identify[index * 2 + 1] = (value ushr 8).toByte()
        }
        fun putAtaString(firstWord: Int, value: String, wordCount: Int) {
            val padded = value.padEnd(wordCount * 2, ' ').take(wordCount * 2)
            for (i in 0 until wordCount) {
                identify[(firstWord + i) * 2] = padded[i * 2 + 1].code.toByte()
                identify[(firstWord + i) * 2 + 1] = padded[i * 2].code.toByte()
            }
        }
        putWord(0, 0x0040)
        putAtaString(10, "SN123456", 10)
        putAtaString(23, "FW1.2", 4)
        putAtaString(27, "OMEGA TEST DRIVE", 20)
        putWord(60, 0xFFFF)
        putWord(61, 0x0FFF)
        putWord(83, 1 shl 10)
        putWord(100, 0x5678)
        putWord(101, 0x1234)
        putWord(102, 0)
        putWord(103, 0)
        val parsed = BotProtocol.parseAtaIdentify(identify)
        assertEquals("SN123456", parsed.serialNumber)
        assertEquals("FW1.2", parsed.firmwareRevision)
        assertEquals("OMEGA TEST DRIVE", parsed.model)
        assertTrue(parsed.supportsLba48)
        assertEquals(0x12345678L, parsed.lba48SectorCount)
        assertEquals(0x12345678L * 512L, parsed.capacityBytes)
    }

    @Test fun fallsBackToLba28WhenLba48IsNotAdvertised() {
        val identify = ByteArray(512)
        identify[0] = 0x40
        identify[120] = 0x34
        identify[121] = 0x12
        identify[122] = 0x02
        val parsed = BotProtocol.parseAtaIdentify(identify)
        assertFalse(parsed.supportsLba48)
        assertEquals(0x021234L, parsed.lba28SectorCount)
        assertEquals(0x021234L * 512L, parsed.capacityBytes)
    }

    @Test fun rejectsAtaIdentifyWithWrongLengthOrInvalidGeneralConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(ByteArray(511)) }
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(ByteArray(512)) }
        val invalid = ByteArray(512)
        invalid[0] = 0xFF.toByte()
        invalid[1] = 0xFF.toByte()
        assertThrows(IllegalArgumentException::class.java) { BotProtocol.parseAtaIdentify(invalid) }
    }

    @Test fun parsesInquiryIdentityFields() {
        val response = ByteArray(36)
        response[0] = 0
        response[1] = 0x80.toByte()
        response[4] = 31
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

    @Test fun rejectsShortInquiryResponse() {
        assertThrows(IllegalArgumentException::class.java) {
            BotProtocol.parseInquiry(ByteArray(35))
        }
    }
}
