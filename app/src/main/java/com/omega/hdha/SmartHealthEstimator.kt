package com.omega.hdha

/**
 * Reports only facts supported by the SMART command responses.
 * A normalized SMART value is not a universal health percentage and is never averaged into one.
 */
data class SmartHealthAssessment(
    val label: String,
    val evidenceCount: Int,
    val thresholdCrossings: List<Int>,
    val explanation: String
)

object SmartHealthEstimator {
    fun assess(sample: SmartSample): SmartHealthAssessment {
        if (sample.thresholdExceeded == true) {
            return SmartHealthAssessment(
                "SMART FAILURE THRESHOLD REPORTED", 0, emptyList(),
                "The drive returned the ATA SMART threshold-exceeded signature. Back up important data and check the drive with a trusted desktop tool."
            )
        }
        if (sample.thresholdExceeded == null) {
            return SmartHealthAssessment(
                "SMART RETURN STATUS UNKNOWN", 0, emptyList(),
                "The ATA return-status signature could not be interpreted. HDHA cannot say that the drive passed or failed."
            )
        }

        val matched = sample.attributes.mapNotNull { attribute ->
            val threshold = sample.thresholds[attribute.id] ?: return@mapNotNull null
            if (threshold !in 1..253 || attribute.current !in 1..253) return@mapNotNull null
            attribute.id to (attribute.current to threshold)
        }.distinctBy { it.first }

        val crossings = matched.filter { (_, values) -> values.first <= values.second }
            .map { it.first }

        return when {
            crossings.isNotEmpty() -> SmartHealthAssessment(
                "ATTRIBUTE AT / BELOW VENDOR THRESHOLD",
                matched.size,
                crossings,
                "One or more normalized attribute values are at or below the threshold reported by this drive. This is a serious vendor-reported warning; back up data and verify with smartctl or the manufacturer's utility."
            )
            else -> SmartHealthAssessment(
                "NO SMART THRESHOLD EXCEEDED",
                matched.size,
                emptyList(),
                "The drive's SMART RETURN STATUS signature indicates no threshold exceeded, and no returned normalized attribute with a non-zero threshold is at/below that threshold. This is not a health percentage and does not prove the drive is healthy or predict remaining life."
            )
        }
    }
}
