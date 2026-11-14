package com.omega.hdha

import org.junit.Assert.*
import org.junit.Test

class SmartInsightsTest {
    private fun sample(
        time: Long = 1L,
        current: Int = 90,
        raw: String = "010000000000",
        status: Boolean? = false
    ) = SmartSample(
        capturedAtMillis = time,
        deviceKey = "serial-123",
        vendor = "Seagate",
        model = "ST1000DM",
        serial = "serial-123",
        profileKey = SmartAttributeProfiles.resolve("Seagate", "ST1000DM").key,
        thresholdExceeded = status,
        attributes = listOf(SmartProtocol.Attribute(5, 0, current, current, raw)),
        thresholds = mapOf(5 to 10)
    )

    @Test fun resolvesVendorProfilesAndFallsBackForUnknownVendors() {
        assertEquals("seagate-st1000dm003", SmartAttributeProfiles.resolve("Seagate", "ST1000DM003").key)
        assertEquals("seagate-family", SmartAttributeProfiles.resolve("Seagate", "ST2000DM").key)
        assertEquals("wd-wd10ezex", SmartAttributeProfiles.resolve("WDC", "WD10EZEX").key)
        assertEquals("western-digital-family", SmartAttributeProfiles.resolve("WDC", "WD20EZAZ").key)
        assertEquals("samsung-family", SmartAttributeProfiles.resolve("Samsung", "870 EVO").key)
        assertEquals("crucial-family", SmartAttributeProfiles.resolve("Crucial", "MX500").key)
        assertEquals("generic-ata", SmartAttributeProfiles.resolve("Mystery", "Unknown").key)
    }

    @Test fun resolvesCommonAttributeNamesWithoutInventingRawUnits() {
        val profile = SmartAttributeProfiles.resolve("Seagate", "ST1000DM")
        assertEquals("Reallocated sector count", SmartAttributeProfiles.describe(profile, 5).label)
        assertTrue(SmartAttributeProfiles.describe(profile, 9).caution.contains("varies"))
        assertEquals("Unknown attribute #250", SmartAttributeProfiles.describe(profile, 250).label)
    }

    @Test fun firstSampleCreatesBaselineInfoButNoTrendClaim() {
        val alerts = SmartTrendAnalyzer.compare(null, sample())
        assertTrue(alerts.any { it.message.contains("First saved sample") })
        assertFalse(alerts.any { it.severity == AlertSeverity.WARNING })
    }

    @Test fun decreasingNormalizedValueForKnownAttributeRaisesWarning() {
        val alerts = SmartTrendAnalyzer.compare(sample(current = 95), sample(time = 2, current = 89))
        assertTrue(alerts.any { it.attributeId == 5 && it.severity == AlertSeverity.WARNING })
    }

    @Test fun unknownRawValueChangeIsInformationalOnly() {
        val old = sample().copy(attributes = listOf(SmartProtocol.Attribute(9, 0, 99, 99, "010000000000")))
        val now = sample(time = 2).copy(attributes = listOf(SmartProtocol.Attribute(9, 0, 99, 99, "020000000000")))
        val alerts = SmartTrendAnalyzer.compare(old, now)
        assertTrue(alerts.any { it.attributeId == 9 && it.severity == AlertSeverity.INFO })
        assertFalse(alerts.any { it.attributeId == 9 && it.severity == AlertSeverity.WARNING })
    }

    @Test fun thresholdCrossingAndReturnStatusAreCritical() {
        val threshold = sample(current = 10)
        assertTrue(SmartTrendAnalyzer.compare(null, threshold).any { it.severity == AlertSeverity.CRITICAL })
        val failed = sample(status = true)
        assertTrue(SmartTrendAnalyzer.compare(null, failed).any {
            it.attributeId == null && it.severity == AlertSeverity.CRITICAL
        })
    }

    @Test fun invalidNormalizedValuesDoNotTriggerThresholdOrTrendAlerts() {
        listOf(0, 254, 255).forEach { invalid ->
            val invalidCurrent = sample(current = invalid)
            val thresholdAlerts = SmartTrendAnalyzer.compare(null, invalidCurrent)
            assertFalse(thresholdAlerts.any {
                it.attributeId == 5 && it.severity == AlertSeverity.CRITICAL
            })

            val previous = sample(time = 1, current = 90)
            val current = sample(time = 2, current = invalid)
            val trendAlerts = SmartTrendAnalyzer.compare(previous, current)
            assertFalse(trendAlerts.any {
                it.attributeId == 5 && it.severity == AlertSeverity.WARNING
            })

            val invalidThreshold = sample(current = 5).copy(thresholds = mapOf(5 to invalid))
            val invalidThresholdAlerts = SmartTrendAnalyzer.compare(null, invalidThreshold)
            assertFalse(invalidThresholdAlerts.any {
                it.attributeId == 5 && it.severity == AlertSeverity.CRITICAL
            })
        }
    }

    @Test fun unknownReturnStatusDoesNotBecomeHealthy() {
        val alerts = SmartTrendAnalyzer.compare(null, sample(status = null))
        assertTrue(alerts.any { it.message.contains("unknown") })
        assertFalse(alerts.any { it.message.contains("healthy", ignoreCase = true) })
    }

    @Test fun missingSerialFallbackUsesUsbPathToSeparateIdenticalDrives() {
        val firstPath = SmartDeviceIdentity.deviceKey("", 0x0BDA, 0x9201, "RTL9201", "/dev/bus/usb/001/002")
        val secondPath = SmartDeviceIdentity.deviceKey("", 0x0BDA, 0x9201, "RTL9201", "/dev/bus/usb/001/003")
        assertNotEquals(firstPath, secondPath)
        assertEquals(firstPath, SmartDeviceIdentity.deviceKey("  ", 0x0BDA, 0x9201, "RTL9201", "/dev/bus/usb/001/002"))
    }

    @Test fun seriallessSamplesDoNotCompareTrendsFromAReusableUsbPath() {
        val key = SmartDeviceIdentity.deviceKey("", 0x0BDA, 0x9201, "ST1000DM", "/dev/bus/usb/001/002")
        val previous = sample(time = 1, current = 95).copy(deviceKey = key, serial = "")
        val current = previous.copy(
            capturedAtMillis = 2,
            attributes = previous.attributes.map { it.copy(current = 80, rawValueHex = "020000000000") }
        )

        val alerts = SmartTrendAnalyzer.compare(previous, current)
        assertFalse(alerts.any { it.message.contains("normalized value fell") })
        assertTrue(alerts.any { it.message.contains("serial is unavailable") })
    }

    @Test fun serialIdentityRemainsStableAcrossUsbPortChanges() {
        val firstPath = SmartDeviceIdentity.deviceKey("SN-123", 0x0BDA, 0x9201, "RTL9201", "/dev/bus/usb/001/002")
        val secondPath = SmartDeviceIdentity.deviceKey(" sn-123 ", 0x0BDA, 0x9201, "RTL9201", "/dev/bus/usb/001/003")
        assertEquals(firstPath, secondPath)
    }

    @Test fun reportIncludesDeviceDataRawValuesAndLimitations() {
        val report = SmartReportFormatter.format(listOf(sample()), 1000L)
        assertTrue(report.contains("HDHA — SMART DRIVE REPORT"))
        assertTrue(report.contains("Seagate ST1000DM"))
        assertTrue(report.contains("raw=010000000000"))
        assertTrue(report.contains("manufacturer/model dependent"))
        assertTrue(report.contains("read-only SMART operations"))
        assertTrue(report.contains("HEALTH PERCENTAGE / REMAINING LIFE: Not calculated"))
        assertFalse(report.contains("HDHA ESTIMATED HEALTH"))
    }

    @Test fun emptyReportIsExplicit() {
        assertTrue(SmartReportFormatter.format(emptyList()).contains("No SMART samples"))
    }

    @Test fun sameOrNewerTimestampIsNotUsedAsTrendBaseline() {
        val current = sample(time = 2, current = 89)
        listOf(
            sample(time = 2, current = 95),
            sample(time = 3, current = 95)
        ).forEach { nonEarlier ->
            val alerts = SmartTrendAnalyzer.compare(nonEarlier, current)
            assertFalse(alerts.any { it.attributeId == 5 && it.severity == AlertSeverity.WARNING })
            assertTrue(alerts.any { it.message.contains("First saved sample") })
            assertFalse(alerts.any { it.message.contains("fell from") })
        }
    }

    @Test fun previousSampleFromAnotherDriveIsIgnored() {
        val previousOtherDrive = sample(current = 95).copy(deviceKey = "other-drive")
        val current = sample(time = 2, current = 89)
        val alerts = SmartTrendAnalyzer.compare(previousOtherDrive, current)
        assertFalse(alerts.any { it.attributeId == 5 && it.severity == AlertSeverity.WARNING })
        assertTrue(alerts.any { it.message.contains("First saved sample") })
        assertFalse(alerts.any { it.message.contains("fell from") })
    }

    @Test fun differentDeviceSamplesAreNotComparedByAnalyzerWhenNoMatchingPreviousProvided() {
        val unrelated = sample().copy(deviceKey = "another-drive", serial = "another-drive")
        val alerts = SmartTrendAnalyzer.compare(null, unrelated)
        assertTrue(alerts.any { it.message.contains("First saved sample") })
    }
    @Test fun sameSerialFromDifferentModelIsNotUsedAsTrendBaseline() {
        val previous = sample(current = 95)
        val current = sample(time = 2, current = 89).copy(model = "WD10EZEX")
        val alerts = SmartTrendAnalyzer.compare(previous, current)
        assertFalse(alerts.any { it.attributeId == 5 && it.severity == AlertSeverity.WARNING })
        assertTrue(alerts.any { it.message.contains("First saved sample") })
    }

    @Test fun sameSerialFromDifferentVendorIsNotUsedAsTrendBaseline() {
        val previous = sample(current = 95)
        val current = sample(time = 2, current = 89).copy(vendor = "Western Digital")
        val alerts = SmartTrendAnalyzer.compare(previous, current)
        assertFalse(alerts.any { it.attributeId == 5 && it.severity == AlertSeverity.WARNING })
        assertTrue(alerts.any { it.message.contains("First saved sample") })
    }

    @Test fun reportCountsOnlySamplesMatchingFullDriveIdentity() {
        val first = sample()
        val otherModel = sample(time = 2).copy(model = "WD10EZEX")
        val report = SmartReportFormatter.format(listOf(first, otherModel), 1000L)
        assertTrue(report.contains("Samples retained: 1"))
        assertFalse(report.contains("CHANGE SINCE PREVIOUS SAMPLE"))
    }

}
