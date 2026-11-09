package com.omega.hdha

import org.junit.Assert.*
import org.junit.Test

class SmartProtocolTest {
    private fun sector(): ByteArray = ByteArray(512).also { it[0] = 1 }

    private fun putEntry(data: ByteArray, index: Int, id: Int, values: ByteArray) {
        val offset = 2 + index * 12
        data[offset] = id.toByte()
        values.copyInto(data, offset + 1, endIndex = minOf(values.size, 11))
    }

    private fun checksum(data: ByteArray) {
        data[511] = 0
        val sum = data.sumOf { it.toInt() and 0xFF } and 0xFF
        data[511] = ((256 - sum) and 0xFF).toByte()
    }

    private fun returnSense(lbaMid: Int, lbaHigh: Int): ByteArray {
        val sense = ByteArray(22)
        sense[0] = 0x72
        sense[7] = 14
        sense[8] = 0x09
        sense[9] = 0x0C
        sense[11] = 0 // ATA error
        sense[16] = lbaMid.toByte()
        sense[18] = lbaHigh.toByte()
        sense[21] = 0x50
        return sense
    }

    @Test fun buildsReadDataCdbWithSmartFeatureAndOneSectorDataIn() {
        val cdb = SmartProtocol.buildReadDataCdb()
        assertEquals(16, cdb.size)
        assertEquals(0x85.toByte(), cdb[0])
        assertEquals(0x09.toByte(), cdb[1])
        assertEquals(0x0E.toByte(), cdb[2])
        assertEquals(0xD0.toByte(), cdb[4])
        assertEquals(1, cdb[6].toInt())
        assertEquals(0x4F.toByte(), cdb[8])
        assertEquals(0xC2.toByte(), cdb[10])
        assertEquals(0xB0.toByte(), cdb[14])
    }

    @Test fun buildsReadThresholdsCdbAsReadOnlyDataIn() {
        val cdb = SmartProtocol.buildReadThresholdsCdb()
        assertEquals(0xD1.toByte(), cdb[4])
        assertEquals(0x0E.toByte(), cdb[2])
        assertEquals(1, cdb[6].toInt())
        assertEquals(0xB0.toByte(), cdb[14])
    }

    @Test fun buildsReturnStatusCdbWithCheckConditionAndNoDataTransfer() {
        val cdb = SmartProtocol.buildReturnStatusCdb()
        assertEquals(0xDA.toByte(), cdb[4])
        assertEquals(0x07.toByte(), cdb[1])
        assertEquals(0x20.toByte(), cdb[2])
        assertEquals(0, cdb[6].toInt())
        assertEquals(0xB0.toByte(), cdb[14])
    }

    @Test fun requestSenseCdbIsReadOnlyAndHasBoundedAllocationLength() {
        val cdb = SmartProtocol.buildRequestSenseCdb()
        assertArrayEquals(byteArrayOf(0x03, 1, 0, 0, 96, 0), cdb)
    }

    @Test fun parsesSmartAttributesAndPreservesRawBytesWithoutDecodingThem() {
        val data = sector()
        putEntry(data, 0, 5, byteArrayOf(0x34, 0x12, 90, 80, 0x11, 0x22, 0, 0, 0, 0, 0))
        checksum(data)
        val parsed = SmartProtocol.parseReadData(data)
        assertEquals(1, parsed.revision)
        assertEquals(1, parsed.attributes.size)
        val attr = parsed.attributes.single()
        assertEquals(5, attr.id)
        assertEquals(0x1234, attr.flags)
        assertEquals(90, attr.current)
        assertEquals(80, attr.worst)
        assertEquals("112200000000", attr.rawValueHex)
    }

    @Test fun parsesAllThirtySmartAttributeSlots() {
        val data = sector()
        for (index in 0 until 30) putEntry(data, index, index + 1, byteArrayOf(1, 0, 100, 90, 0, 0, 0, 0, 0, 0, 0))
        checksum(data)
        assertEquals(30, SmartProtocol.parseReadData(data).attributes.size)
    }

    @Test fun rejectsSmartDataWithWrongLengthOrChecksum() {
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseReadData(ByteArray(511)) }
        val bad = sector()
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseReadData(bad) }
    }

    @Test fun rejectsDuplicateSmartAttributeIds() {
        val data = sector()
        putEntry(data, 0, 9, ByteArray(11))
        putEntry(data, 1, 9, ByteArray(11))
        checksum(data)
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseReadData(data) }
    }

    @Test fun parsesThresholdsByAttributeId() {
        val data = sector()
        putEntry(data, 0, 5, byteArrayOf(10) + ByteArray(10))
        putEntry(data, 1, 197, byteArrayOf(3) + ByteArray(10))
        checksum(data)
        assertEquals(mapOf(5 to 10, 197 to 3), SmartProtocol.parseReadThresholds(data))
    }

    @Test fun rejectsDuplicateThresholdIdsAndInvalidChecksum() {
        val duplicate = sector()
        putEntry(duplicate, 0, 5, ByteArray(11))
        putEntry(duplicate, 1, 5, ByteArray(11))
        checksum(duplicate)
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseReadThresholds(duplicate) }
        val invalid = sector()
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseReadThresholds(invalid) }
    }

    @Test fun parsesSmartReturnStatusUsingLowLbaBytesNotExpandedBytes() {\n        val sense = returnSense(0x4F, 0xC2).apply { this[17] = 0x11; this[19] = 0x22 }\n        assertFalse(SmartProtocol.parseAtaReturnDescriptor(sense).thresholdExceeded!!)\n    }\n\n    @Test fun parsesSmartReturnStatusPassSignature() {
        val parsed = SmartProtocol.parseAtaReturnDescriptor(returnSense(0x4F, 0xC2))
        assertFalse(parsed.thresholdExceeded!!)
        assertEquals(0x50, parsed.status)
    }

    @Test fun parsesSmartReturnStatusThresholdExceededSignature() {
        val parsed = SmartProtocol.parseAtaReturnDescriptor(returnSense(0xF4, 0x2C))
        assertTrue(parsed.thresholdExceeded!!)
    }

    @Test fun unknownSmartReturnStatusSignatureRemainsUnknown() {
        val parsed = SmartProtocol.parseAtaReturnDescriptor(returnSense(0x11, 0x22))
        assertNull(parsed.thresholdExceeded)
    }

    @Test fun ataErrorOrErrorStatusBitPreventsPassVerdict() {
        val errorRegister = returnSense(0x4F, 0xC2).apply { this[11] = 4 }
        assertNull(SmartProtocol.parseAtaReturnDescriptor(errorRegister).thresholdExceeded)
        val errorStatus = returnSense(0x4F, 0xC2).apply { this[21] = 0x51 }
        assertNull(SmartProtocol.parseAtaReturnDescriptor(errorStatus).thresholdExceeded)
    }

    @Test fun noDataBotCommandUsesZeroTransferLengthAndNoDirectionFlag() {
        val cbw = BotProtocol.buildCbw(9, 0, SmartProtocol.buildReturnStatusCdb(), dataIn = false)
        assertEquals(31, cbw.size)
        assertEquals(0, cbw[8].toInt())
        assertEquals(0, cbw[9].toInt())
        assertEquals(0, cbw[10].toInt())
        assertEquals(0, cbw[11].toInt())
        assertEquals(0, cbw[12].toInt())
    }

    @Test fun rejectsMissingMalformedAndTruncatedAtaReturnDescriptors() {
        val noDescriptor = ByteArray(8).apply { this[0] = 0x72; this[7] = 0 }
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseAtaReturnDescriptor(noDescriptor) }
        val badLength = returnSense(0x4F, 0xC2).apply { this[9] = 11 }
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseAtaReturnDescriptor(badLength) }
        val truncated = returnSense(0x4F, 0xC2).apply { this[7] = 13 }
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseAtaReturnDescriptor(truncated) }
        val wrongFormat = returnSense(0x4F, 0xC2).apply { this[0] = 0x70 }
        assertThrows(IllegalArgumentException::class.java) { SmartProtocol.parseAtaReturnDescriptor(wrongFormat) }
    }
}
