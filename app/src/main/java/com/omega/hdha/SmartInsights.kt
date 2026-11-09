package com.omega.hdha

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class AttributeDirection { LOWER_NORMALIZED_IS_WORSE, HIGHER_NORMALIZED_IS_WORSE, UNKNOWN }

data class AttributeProfile(
    val id: Int,
    val label: String,
    val direction: AttributeDirection = AttributeDirection.UNKNOWN,
    val caution: String = "Vendor-specific raw value; interpretation is not standardized."
)

/** Add exact model/vendor overrides here as evidence becomes available. Unknown models use conservative common labels only. */
data class SmartProfile(
    val key: String,
    val vendorTokens: List<String>,
    val modelTokens: List<String>,
    val attributes: Map<Int, AttributeProfile>,
    val confidenceNote: String
) {
    fun matches(vendor: String, model: String): Boolean {
        val v = vendor.trim().uppercase()
        val m = model.trim().uppercase()
        val vendorMatch = vendorTokens.isEmpty() || vendorTokens.any { v.contains(it) }
        val modelMatch = modelTokens.isEmpty() || modelTokens.any { m.contains(it) }
        return vendorMatch && modelMatch
    }
}

object SmartAttributeProfiles {
    private val common = listOf(
        AttributeProfile(1, "Read error rate"),
        AttributeProfile(3, "Spin-up time"),
        AttributeProfile(4, "Start/stop count"),
        AttributeProfile(5, "Reallocated sector count", AttributeDirection.LOWER_NORMALIZED_IS_WORSE),
        AttributeProfile(7, "Seek error rate"),
        AttributeProfile(9, "Power-on hours", caution = "Raw value encoding varies by manufacturer."),
        AttributeProfile(10, "Spin retry count"),
        AttributeProfile(12, "Power cycle count"),
        AttributeProfile(187, "Reported uncorrectable errors", AttributeDirection.LOWER_NORMALIZED_IS_WORSE),
        AttributeProfile(188, "Command timeout", AttributeDirection.LOWER_NORMALIZED_IS_WORSE),
        AttributeProfile(190, "Airflow temperature", caution = "Temperature encoding varies by manufacturer."),
        AttributeProfile(194, "Temperature", caution = "Temperature encoding varies by manufacturer."),
        AttributeProfile(196, "Reallocation event count", AttributeDirection.LOWER_NORMALIZED_IS_WORSE),
        AttributeProfile(197, "Current pending sectors", AttributeDirection.LOWER_NORMALIZED_IS_WORSE),
        AttributeProfile(198, "Offline uncorrectable sectors", AttributeDirection.LOWER_NORMALIZED_IS_WORSE),
        AttributeProfile(199, "UDMA CRC error count", caution = "Raw value is vendor encoded; compare only like-for-like samples."),
        AttributeProfile(200, "Write error rate"),
        AttributeProfile(241, "Total LBAs written", caution = "Raw value encoding and units vary by manufacturer."),
        AttributeProfile(242, "Total LBAs read", caution = "Raw value encoding and units vary by manufacturer.")
    ).associateBy { it.id }

    private val profiles = listOf(
        SmartProfile("seagate-st1000dm003", listOf("SEAGATE"), listOf("ST1000DM003"), common,
            "Exact model profile entry; attribute raw encodings remain unverified and are not converted."),
        SmartProfile("wd-wd10ezex", listOf("WDC", "WESTERN DIGITAL"), listOf("WD10EZEX"), common,
            "Exact model profile entry; attribute raw encodings remain unverified and are not converted."),
        SmartProfile("generic-ata", emptyList(), emptyList(), common,
            "Generic ATA labels only. Attribute names and raw encodings can vary by model."),
        SmartProfile("seagate-family", listOf("SEAGATE"), emptyList(), common,
            "Seagate family labels; raw six-byte values are preserved, not converted to units."),
        SmartProfile("western-digital-family", listOf("WDC", "WESTERN DIGITAL"), emptyList(), common,
            "Western Digital family labels; raw six-byte values are preserved, not converted to units."),
        SmartProfile("samsung-family", listOf("SAMSUNG"), emptyList(), common,
            "Samsung family labels; raw six-byte values are preserved, not converted to units."),
        SmartProfile("crucial-family", listOf("CRUCIAL", "MICRON"), emptyList(), common,
            "Crucial/Micron family labels; raw six-byte values are preserved, not converted to units.")
    )

    fun resolve(vendor: String, model: String): SmartProfile =
        profiles.firstOrNull { it.key != "generic-ata" && it.matches(vendor, model) }
            ?: profiles.first { it.key == "generic-ata" }

    fun describe(profile: SmartProfile, id: Int): AttributeProfile =
        profile.attributes[id] ?: AttributeProfile(id, "Unknown attribute #$id")
}

data class SmartSample(
    val capturedAtMillis: Long,
    val deviceKey: String,
    val vendor: String,
    val model: String,
    val serial: String,
    val profileKey: String,
    val thresholdExceeded: Boolean?,
    val attributes: List<SmartProtocol.Attribute>,
    val thresholds: Map<Int, Int>
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }

data class SmartAlert(val attributeId: Int?, val severity: AlertSeverity, val message: String)

object SmartTrendAnalyzer {
    fun compare(previous: SmartSample?, current: SmartSample): List<SmartAlert> {
        val alerts = mutableListOf<SmartAlert>()
        when (current.thresholdExceeded) {
            true -> alerts += SmartAlert(null, AlertSeverity.CRITICAL,
                "Drive reports SMART threshold exceeded. Back up important data and investigate promptly.")
            null -> alerts += SmartAlert(null, AlertSeverity.INFO,
                "SMART RETURN STATUS is unknown; no overall health verdict is available.")
            false -> Unit
        }
        val hasMatchingPrevious = previous?.deviceKey == current.deviceKey
        val prior = previous
            ?.takeIf { hasMatchingPrevious }
            ?.attributes
            ?.associateBy { it.id }
            .orEmpty()
        val profile = SmartAttributeProfiles.resolve(current.vendor, current.model)
        current.attributes.forEach { now ->
            val definition = SmartAttributeProfiles.describe(profile, now.id)
            val threshold = current.thresholds[now.id]
            if (threshold != null && threshold > 0 && now.current <= threshold) {
                alerts += SmartAlert(now.id, AlertSeverity.CRITICAL,
                    "${definition.label}: normalized value ${now.current} is at/below threshold $threshold.")
            }
            val old = prior[now.id] ?: return@forEach
            when (definition.direction) {
                AttributeDirection.LOWER_NORMALIZED_IS_WORSE -> {
                    if (now.current < old.current) alerts += SmartAlert(now.id, AlertSeverity.WARNING,
                        "${definition.label}: normalized value fell from ${old.current} to ${now.current}.")
                }
                AttributeDirection.HIGHER_NORMALIZED_IS_WORSE -> {
                    if (now.current > old.current) alerts += SmartAlert(now.id, AlertSeverity.WARNING,
                        "${definition.label}: normalized value rose from ${old.current} to ${now.current}.")
                }
                AttributeDirection.UNKNOWN -> Unit
            }
            if (now.rawValueHex != old.rawValueHex && definition.direction == AttributeDirection.UNKNOWN) {
                alerts += SmartAlert(now.id, AlertSeverity.INFO,
                    "${definition.label}: raw vendor bytes changed; trend direction is unknown.")
            }
        }
        if (!hasMatchingPrevious && current.thresholdExceeded != true && alerts.none { it.severity == AlertSeverity.CRITICAL }) {
            alerts += SmartAlert(null, AlertSeverity.INFO,
                "First saved sample: future comparisons will establish a baseline; no trend is inferred yet.")
        }
        return alerts.distinctBy { Triple(it.attributeId, it.severity, it.message) }
    }
}

class SmartHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("hdha_smart_history_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun append(sample: SmartSample): List<SmartSample> {
        val all = readAll().filterNot {
            it.deviceKey == sample.deviceKey && it.capturedAtMillis == sample.capturedAtMillis
        } + sample
        val bounded = all.sortedBy { it.capturedAtMillis }.takeLast(MAX_SAMPLES)
        preferences.edit().putString(KEY, JSONArray().apply {
            bounded.forEach { put(encode(it)) }
        }.toString()).apply()
        return bounded
    }

    @Synchronized
    fun readAll(): List<SmartSample> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { decode(array.getJSONObject(index)) }.getOrNull()
            }
        } catch (_: Exception) { emptyList() }
    }

    fun latestFor(deviceKey: String): SmartSample? =
        readAll().filter { it.deviceKey == deviceKey }.maxByOrNull { it.capturedAtMillis }

    fun report(samples: List<SmartSample>, generatedAtMillis: Long = System.currentTimeMillis()): String =
        SmartReportFormatter.format(samples, generatedAtMillis)

    private fun encode(sample: SmartSample) = JSONObject().apply {
        put("time", sample.capturedAtMillis)
        put("deviceKey", sample.deviceKey)
        put("vendor", sample.vendor)
        put("model", sample.model)
        put("serial", sample.serial)
        put("profile", sample.profileKey)
        if (sample.thresholdExceeded == null) put("status", JSONObject.NULL) else put("status", sample.thresholdExceeded)
        put("attributes", JSONArray().apply {
            sample.attributes.forEach { a -> put(JSONObject().apply {
                put("id", a.id); put("flags", a.flags); put("current", a.current)
                put("worst", a.worst); put("raw", a.rawValueHex)
            }) }
        })
        put("thresholds", JSONObject().apply { sample.thresholds.forEach { (id, value) -> put(id.toString(), value) } })
    }

    private fun decode(json: JSONObject): SmartSample {
        val attributesJson = json.getJSONArray("attributes")
        val attributes = (0 until attributesJson.length()).map { i ->
            val a = attributesJson.getJSONObject(i)
            SmartProtocol.Attribute(a.getInt("id"), a.getInt("flags"), a.getInt("current"),
                a.getInt("worst"), a.getString("raw"))
        }
        val thresholdJson = json.getJSONObject("thresholds")
        val thresholds = thresholdJson.keys().asSequence().associate { it.toInt() to thresholdJson.getInt(it) }
        val status = json.opt("status")
        return SmartSample(json.getLong("time"), json.getString("deviceKey"), json.optString("vendor"),
            json.optString("model"), json.optString("serial"), json.optString("profile"),
            if (status == null || status == JSONObject.NULL) null else json.getBoolean("status"),
            attributes, thresholds)
    }

    companion object {
        private const val KEY = "samples"
        private const val MAX_SAMPLES = 500
    }
}

object SmartReportFormatter {
    fun format(samples: List<SmartSample>, generatedAtMillis: Long = System.currentTimeMillis()): String {
        if (samples.isEmpty()) return "HDHA SMART REPORT\nNo SMART samples have been saved yet.\n"
        val sorted = samples.sortedBy { it.capturedAtMillis }
        val latest = sorted.last()
        val previous = sorted.dropLast(1).lastOrNull { it.deviceKey == latest.deviceKey }
        val profile = SmartAttributeProfiles.resolve(latest.vendor, latest.model)
        val alerts = SmartTrendAnalyzer.compare(previous, latest)
        return buildString {
            appendLine("HDHA — SMART DRIVE REPORT")
            appendLine("Generated: ${java.time.Instant.ofEpochMilli(generatedAtMillis)}")
            appendLine("Device: ${latest.vendor.ifBlank { "Unknown vendor" }} ${latest.model.ifBlank { "Unknown model" }}")
            appendLine("Serial: ${latest.serial.ifBlank { "Not supplied" }}")
            appendLine("Profile: ${profile.key}")
            appendLine("Samples retained: ${sorted.count { it.deviceKey == latest.deviceKey }}")
            appendLine("SMART RETURN STATUS: ${when (latest.thresholdExceeded) { true -> "THRESHOLD EXCEEDED"; false -> "NO THRESHOLD EXCEEDED"; null -> "UNKNOWN" }}")
            val health = SmartHealthEstimator.assess(latest)
            appendLine("HDHA ESTIMATED HEALTH: ${health.percentage?.let { "$it% — ${health.label}" } ?: health.label}")
            appendLine("ESTIMATE CONFIDENCE: ${health.confidence}; evidence attributes: ${health.evidenceCount}")
            appendLine("ESTIMATE NOTE: ${health.explanation}")
            if (health.percentage != null && previous != null) {
                val previousHealth = SmartHealthEstimator.assess(previous)
                if (previousHealth.percentage != null) {
                    val delta = health.percentage - previousHealth.percentage
                    appendLine("CHANGE SINCE PREVIOUS SAMPLE: ${if (delta > 0) "+" else ""}$delta percentage points")
                }
            }
            appendLine()
            appendLine("ATTRIBUTES")
            latest.attributes.forEach { attribute ->
                val definition = SmartAttributeProfiles.describe(profile, attribute.id)
                appendLine("#${attribute.id} ${definition.label}: current=${attribute.current}, worst=${attribute.worst}, threshold=${latest.thresholds[attribute.id] ?: "N/A"}, raw=${attribute.rawValueHex}")
            }
            appendLine()
            appendLine("ALERTS / COMPARISON")
            alerts.forEach { appendLine("[${it.severity}] ${it.message}") }
            appendLine()
            appendLine("LIMITATIONS")
            appendLine("- SMART values and raw encodings are manufacturer/model dependent.")
            appendLine("- Unknown attributes and raw values are not assigned guessed units or health meanings.")
            appendLine("- A clean SMART status does not guarantee that a drive will not fail.")
            appendLine("- Trend alerts compare saved samples; they are not a diagnosis or failure prediction.")
            appendLine("- HDHA performs read-only SMART operations; no enable/disable, writes, repair, or formatting.")
        }
    }
}
