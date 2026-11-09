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

    @Test fun calculatesWeightedPercentageFromSupportedNormalizedAttributes() {
        val result = SmartHealthEstimator.assess(sample())
        assertNotNull(result.percentage)
        assertEquals(96, result.percentage)
        assertEquals("Excellent", result.label)
        assertEquals(2, result.evidenceCount)
        assertEquals("Limited", result.confidence)
    }

    @Test fun doesNotCalculatePercentageWhenSmartReturnStatusIsUnknown() {
        val result = SmartHealthEstimator.assess(sample(status = null))
        assertNull(result.percentage)
        assertEquals("Unknown", result.label)
    }

    @Test fun doesNotCalculatePercentageWhenDriveReportsThresholdExceeded() {
        val result = SmartHealthEstimator.assess(sample(status = true))
        assertNull(result.percentage)
        assertEquals("Critical", result.label)
    }

    @Test fun doesNotCalculatePercentageWithInsufficientEvidence() {
        val oneAttribute = sample().copy(
            attributes = sample().attributes.take(1),
            thresholds = mapOf(5 to 10)
        )
        val result = SmartHealthEstimator.assess(oneAttribute)
        assertNull(result.percentage)
        assertEquals("Insufficient data", result.label)
    }

    @Test fun doesNotCalculatePercentageWhenSupportedAttributeCrossesItsThreshold() {
        val result = SmartHealthEstimator.assess(sample(first = 10, second = 95))
        assertNull(result.percentage)
        assertEquals("Critical attribute threshold", result.label)
    }

    @Test fun ignoresRawValuesAndUnsupportedAttributesInsteadOfGuessingUnits() {
        val onlyUnknown = sample().copy(
            attributes = listOf(SmartProtocol.Attribute(9, 0, 99, 99, "FFFFFFFFFFFF")),
            thresholds = mapOf(9 to 1)
        )
        val result = SmartHealthEstimator.assess(onlyUnknown)
        assertNull(result.percentage)
        assertEquals(0, result.evidenceCount)
    }

    @Test fun percentageIsBoundedToZeroThroughOneHundred() {
        val result = SmartHealthEstimator.assess(sample(first = 100, second = 100))
        assertEquals(100, result.percentage)
    }
}
