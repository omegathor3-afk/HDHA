package com.omega.hdha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMassStorageProfileTest {
    private fun endpoint(direction: String, type: String = "BULK") =
        UsbEndpointInfo(1, direction, type, 512)

    @Test fun recognizesBulkOnlyWithRequiredEndpoints() {
        val profile = UsbMassStorageProfile.inspect(
            UsbInterfaceInfo(0, 8, 6, 0x50, listOf(endpoint("IN"), endpoint("OUT")))
        )
        assertNotNull(profile)
        assertEquals(MassStorageTransport.BULK_ONLY, profile!!.transport)
        assertTrue(profile.canAttemptBulkOnlyTransport)
    }

    @Test fun bulkOnlyWithoutBulkInCannotBeAttempted() {
        val profile = UsbMassStorageProfile.inspect(
            UsbInterfaceInfo(0, 8, 6, 0x50, listOf(endpoint("OUT")))
        )
        assertNotNull(profile)
        assertFalse(profile!!.canAttemptBulkOnlyTransport)
    }

    @Test fun uasIsDetectedButNotClaimedAsSupported() {
        val profile = UsbMassStorageProfile.inspect(
            UsbInterfaceInfo(0, 8, 6, 0x62, listOf(endpoint("IN"), endpoint("OUT")))
        )
        assertEquals(MassStorageTransport.UAS, profile!!.transport)
        assertFalse(profile.canAttemptBulkOnlyTransport)
    }

    @Test fun nonMassStorageInterfaceReturnsNull() {
        assertNull(
            UsbMassStorageProfile.inspect(
                UsbInterfaceInfo(0, 3, 1, 2, listOf(endpoint("IN"), endpoint("OUT")))
            )
        )
    }

    @Test fun unknownProtocolIsNotAssumedToBeBulkOnly() {
        val profile = UsbMassStorageProfile.inspect(
            UsbInterfaceInfo(0, 8, 6, 0x7F, listOf(endpoint("IN"), endpoint("OUT")))
        )
        assertEquals(MassStorageTransport.UNKNOWN, profile!!.transport)
        assertFalse(profile.canAttemptBulkOnlyTransport)
    }
}
