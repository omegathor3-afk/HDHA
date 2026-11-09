package com.omega.hdha

import org.junit.Assert.*
import org.junit.Test

class SmartHealthEstimatorTest {
    private fun sample(
        status: Boolean? = false,
        first: Int = 98,
        second: Int = 95,
        threshold: Int = 10
    ): SmartSample = SmartSample(
        capturedAtMillis = 1L,
        deviceKey = "drive-1",
        vendor = "Example",
        model = "Model X",
        serial = "drive-1",
        profileKey = "generic-ata",
        thresholdExceeded = status,
        attributes = listOf(
            SmartProtocol.Attribute(5, 0, first, first, "010000000000"),
            SmartProtocol.Attribute(197, 0, second, second, "000000000000")
        ),
        thresholds = mapOf(5 to threshold, 197 to threshold)
    )

    @Test fun neverInventsUniversalHealthPercentage() {
        val result = SmartHealthEstimator.assess(sample())
        assertNull(result.percentage)
        assertEquals("No SMART threshold exceeded", result.label)
        assertEquals(2, result.evidenceCount)
        assertTrue(result.explanation.contains("does not guarantee"))
    }

    @Test fun unknownReturnStatusIsUnavailableNotHealthy() {
        val result = SmartHealthEstimator.assess(sample(status = null))
        assertNull(result.percentage)
        assertEquals("SMART status unknown", result.label)
    }

    @Test fun driveReportedThresholdFailureIsCritical() {
        val result = SmartHealthEstimator.assess(sample(status = true))
        assertNull(result.percentage)
        assertEquals("SMART threshold exceeded", result.label)
    }

    @Test fun normalizedAttributeAtThresholdIsFlagged() {
        val result = SmartHealthEstimator.assess(sample(first = 10, second = 95))
        assertNull(result.percentage)
        assertEquals("SMART attribute threshold reached", result.label)
        assertTrue(result.explanation.contains("ID 5"))
    }

    @Test fun noRelevantAttributesDoesNotBecomeHealthyOrCreateScore() {
        val onlyUnknown = sample().copy(
            attributes = listOf(SmartProtocol.Attribute(9, 0, 99, 99, "FFFFFFFFFFFF")),
            thresholds = mapOf(9 to 1)
        )
        val result = SmartHealthEstimator.assess(onlyUnknown)
        assertNull(result.percentage)
        assertEquals(0, result.evidenceCount)
        assertEquals("No SMART threshold exceeded", result.label)
    }

    @Test fun invalidNormalizedValuesAreNotTreatedAsAHealthScore() {
        val invalid = sample().copy(
            attributes = listOf(
                SmartProtocol.Attribute(5, 0, 255, 255, "010000000000"),
                SmartProtocol.Attribute(197, 0, 0, 0, "000000000000")
            )
        )
        val result = SmartHealthEstimator.assess(invalid)
        assertNull(result.percentage)
        assertFalse(result.label.contains("Excellent"))
    }
}
