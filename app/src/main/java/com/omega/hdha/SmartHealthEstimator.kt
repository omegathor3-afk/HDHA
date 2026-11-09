package com.omega.hdha

data class SmartHealthAssessment(
    val percentage: Int?,
    val label: String,
    val confidence: String,
    val evidenceCount: Int,
    val explanation: String
)

object SmartHealthEstimator {
    private val weights = mapOf(
        5 to 4, 10 to 2, 187 to 3, 188 to 2, 196 to 3, 197 to 4, 198 to 4
    )

    fun assess(sample: SmartSample): SmartHealthAssessment {
        if (sample.thresholdExceeded == true) {
            return SmartHealthAssessment(null, "Critical", "High", 0,
                "The drive reports SMART threshold exceeded. Back up data immediately; no percentage is shown.")
        }
        if (sample.thresholdExceeded == null) {
            return SmartHealthAssessment(null, "Unknown", "None", 0,
                "SMART return status is unknown, so HDHA will not calculate a health percentage.")
        }

        val evidence = sample.attributes.mapNotNull { attribute ->
            val threshold = sample.thresholds[attribute.id] ?: return@mapNotNull null
            val weight = weights[attribute.id] ?: return@mapNotNull null
            if (threshold <= 0 || attribute.current !in 0..100) return@mapNotNull null
            Triple(attribute.current, weight, attribute.id)
        }.distinctBy { it.third }

        if (evidence.size < 2) {
            return SmartHealthAssessment(null, "Insufficient data", "None", evidence.size,
                "At least two supported normalized health attributes with valid thresholds are required.")
        }

        val totalWeight = evidence.sumOf { it.second }
        val weightedScore = evidence.sumOf { it.first * it.second } / totalWeight.toDouble()
        val score = weightedScore.toInt().coerceIn(0, 100)
        val label = when {
            score >= 90 -> "Excellent"
            score >= 75 -> "Good"
            score >= 50 -> "Caution"
            else -> "Poor"
        }
        val confidence = if (evidence.size >= 4) "Moderate" else "Limited"
        return SmartHealthAssessment(score, label, confidence, evidence.size,
            "Estimated from " + evidence.size + " supported normalized SMART attributes. Vendor raw values are not converted or guessed.")
    }
}
