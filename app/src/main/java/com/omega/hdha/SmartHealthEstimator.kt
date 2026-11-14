package com.omega.hdha

/**
 * Reports only facts supported by the SMART command responses.
 * Normalized SMART values are vendor-defined and are never averaged into a universal health score.
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
                "SMART FAILURE THRESHOLD REPORTED",
                0,
                emptyList(),
                "The drive returned the ATA SMART threshold-exceeded signature. Back up important data and verify with a trusted desktop tool."
            )
        }
        if (sample.thresholdExceeded == null) {
            return SmartHealthAssessment(
                "SMART RETURN STATUS UNKNOWN",
                0,
                emptyList(),
                "The ATA return-status signature could not be interpreted. HDHA cannot say that the drive passed or failed."
            )
        }

        val comparable = sample.attributes.mapNotNull { attribute ->
            val threshold = sample.thresholds[attribute.id] ?: return@mapNotNull null
            if (threshold !in 1..253 || attribute.current !in 1..253) return@mapNotNull null
            attribute.id to (attribute.current to threshold)
        }.distinctBy { it.first }

        val crossings = comparable
            .filter { (_, values) -> values.first <= values.second }
            .map { it.first }

        return if (crossings.isNotEmpty()) {
            SmartHealthAssessment(
                "ATTRIBUTE AT / BELOW VENDOR THRESHOLD",
                comparable.size,
                crossings,
                "One or more normalized attribute values are at or below the threshold reported by this drive. This is a serious vendor-reported warning; back up data and verify with smartctl or the manufacturer's utility."
            )
        } else {
            SmartHealthAssessment(
                "NO SMART THRESHOLD EXCEEDED",
                comparable.size,
                emptyList(),
                "The drive's SMART RETURN STATUS signature indicates no threshold exceeded, and no returned normalized attribute with a non-zero threshold is at/below that threshold. This does not prove the drive is healthy or predict remaining life."
            )
        }
    }
}
