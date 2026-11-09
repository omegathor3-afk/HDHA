package com.omega.hdha

/**
 * Reports only SMART evidence that the drive/bridge actually returned.
 *
 * ATA normalized attribute values are vendor-defined health indicators, not a
 * universal percentage scale. Averaging them into a "health %" is misleading,
 * so this class deliberately never calculates or returns a percentage.
 */
data class SmartHealthAssessment(
    val percentage: Int?,
    val label: String,
    val confidence: String,
    val evidenceCount: Int,
    val explanation: String
)

object SmartHealthEstimator {
    private val thresholdRelevantIds = setOf(5, 10, 187, 188, 196, 197, 198)

    fun assess(sample: SmartSample): SmartHealthAssessment {
        if (sample.thresholdExceeded == true) {
            return SmartHealthAssessment(
                null, "SMART threshold exceeded", "Drive-reported", 0,
                "The drive reported SMART RETURN STATUS threshold exceeded. Back up important data immediately. This is a drive-reported warning, not a prediction of remaining life."
            )
        }
        if (sample.thresholdExceeded == null) {
            return SmartHealthAssessment(
                null, "SMART status unknown", "Unavailable", 0,
                "The ATA SMART RETURN STATUS could not be interpreted. HDHA cannot give an overall health verdict."
            )
        }

        val relevant = sample.attributes
            .filter { it.id in thresholdRelevantIds && sample.thresholds[it.id]?.let { value -> value > 0 } == true }
            .distinctBy { it.id }
        val crossed = relevant.filter { attribute ->
            attribute.current in 1..100 &&
                attribute.current <= (sample.thresholds[attribute.id] ?: Int.MIN_VALUE)
        }

        if (crossed.isNotEmpty()) {
            val ids = crossed.joinToString { "ID ${it.id}" }
            return SmartHealthAssessment(
                null, "SMART attribute threshold reached", "Drive-reported", relevant.size,
                "Normalized SMART threshold reached for $ids. Treat this as a warning and back up important data. No percentage is calculated."
            )
        }

        return SmartHealthAssessment(
            null, "No SMART threshold exceeded", "Drive-reported", relevant.size,
            "The drive's SMART RETURN STATUS reports no threshold exceeded. This is not a complete health score and does not guarantee reliability. HDHA does not convert normalized values into a percentage."
        )
    }
}
