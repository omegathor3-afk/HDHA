package com.omega.hdha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceInspectorTest {
    @Test fun massStorageClassIsRecognized() {
        val info = UsbInterfaceInfo(0, 8, 6, 80, emptyList())
        assertTrue(info.isMassStorage)
    }
    @Test fun nonStorageClassIsNotRecognizedAsDrive() {
        val info = UsbInterfaceInfo(0, 3, 1, 2, emptyList())
        assertFalse(info.isMassStorage)
    }
}
