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
