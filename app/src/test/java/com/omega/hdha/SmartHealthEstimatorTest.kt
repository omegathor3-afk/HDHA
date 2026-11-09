package com.omega.hdha

import org.junit.Assert.*
import org.junit.Test

class SmartHealthEstimatorTest {
    private fun sample(status: Boolean? = false, first: Int = 98, second: Int = 95): SmartSample =
        SmartSample(
            capturedAtMillis = 1L, deviceKey = "drive-1", vendor = "Example",
            model = "Model X", serial = "drive-1", profileKey = "generic-ata",
            thresholdExceeded = status,
            attributes = listOf(
                SmartProtocol.Attribute(5, 0, first, first, "010000000000"),
                SmartProtocol.Attribute(197, 0, second, second, "000000000000")
            ),
            thresholds = mapOf(5 to 10, 197 to 10)
        )

    @Test fun clearStatusIsNotMisrepresentedAsHealthyOrAsPercentage() {
        val result = SmartHealthEstimator.assess(sample())
        assertEquals("NO SMART THRESHOLD EXCEEDED", result.label)
        assertTrue(result.explanation.contains("does not prove"))
        assertEquals(2, result.evidenceCount)
        assertTrue(result.thresholdCrossings.isEmpty())
    }

    @Test fun unknownStatusNeverPassesAsHealthy() {
        val result = SmartHealthEstimator.assess(sample(status = null))
        assertEquals("SMART RETURN STATUS UNKNOWN", result.label)
    }

    @Test fun driveReportedFailureIsCriticalEvidence() {
        val result = SmartHealthEstimator.assess(sample(status = true))
        assertEquals("SMART FAILURE THRESHOLD REPORTED", result.label)
    }

    @Test fun normalizedValueAtThresholdIsReported() {
        val result = SmartHealthEstimator.assess(sample(first = 10))
        assertEquals("ATTRIBUTE AT / BELOW VENDOR THRESHOLD", result.label)
        assertEquals(listOf(5), result.thresholdCrossings)
    }

    @Test fun normalizedValueBelowThresholdIsReported() {
        val result = SmartHealthEstimator.assess(sample(first = 4))
        assertEquals(listOf(5), result.thresholdCrossings)
    }

    @Test fun zeroThresholdAndInvalidNormalizedValuesAreNotCounted() {
        val result = SmartHealthEstimator.assess(sample().copy(
            attributes = listOf(
                SmartProtocol.Attribute(5, 0, 0, 0, "000000000000"),
                SmartProtocol.Attribute(197, 0, 254, 254, "000000000000")
            ),
            thresholds = mapOf(5 to 0, 197 to 254)
        ))
        assertEquals(0, result.evidenceCount)
        assertEquals("NO SMART THRESHOLD EXCEEDED", result.label)
    }

    @Test fun vendorRawBytesAreNeverInterpretedAsUniversalHealth() {
        val result = SmartHealthEstimator.assess(sample().copy(
            attributes = listOf(SmartProtocol.Attribute(9, 0, 99, 99, "FFFFFFFFFFFF")),
            thresholds = mapOf(9 to 1)
        ))
        assertEquals(0, result.evidenceCount)
        assertTrue(result.explanation.contains("does not prove"))
    }
}
