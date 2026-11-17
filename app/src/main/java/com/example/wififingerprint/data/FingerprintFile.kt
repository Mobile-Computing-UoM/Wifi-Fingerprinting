package com.example.wififingerprint.data

import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import kotlin.math.roundToInt

data class FingerprintFileMetadata(
    val version: Int = 1,
    val deviceId: String = "unknown",
    val model: String = "Unknown Device",
    val sdk: Int = 0,
    val exportedAt: Long = System.currentTimeMillis(),
    val calibA: Double = 1.0,
    val calibB: Double = 0.0
)

object FingerprintFile {

    private const val HEADER_VERSION = "#fp_version="
    private const val HEADER_DEVICE_ID = "#device_id="
    private const val HEADER_MODEL = "#model="
    private const val HEADER_SDK = "#sdk="
    private const val HEADER_EXPORTED_AT = "#exported_at="
    private const val HEADER_CALIB_A = "#calib_a="
    private const val HEADER_CALIB_B = "#calib_b="

    private const val CSV_COLUMNS = "timestamp,spot_label,coord_x,coord_y,bssid,ssid,sample_count,samples,avg_rssi"

    /**
     * Serializes survey spots and metadata into a shareable string format with standard headers.
     * All BSSIDs are strictly normalized to 'anon_<hex>' for cross-device consistency and privacy.
     */
    fun serialize(metadata: FingerprintFileMetadata, spots: List<RawSurveySpot>): String {
        val sb = StringBuilder()
        sb.append("$HEADER_VERSION${metadata.version}\n")
        sb.append("$HEADER_DEVICE_ID${metadata.deviceId}\n")
        sb.append("$HEADER_MODEL${metadata.model}\n")
        sb.append("$HEADER_SDK${metadata.sdk}\n")
        sb.append("$HEADER_EXPORTED_AT${metadata.exportedAt}\n")
        sb.append("$HEADER_CALIB_A${metadata.calibA}\n")
        sb.append("$HEADER_CALIB_B${metadata.calibB}\n")
        sb.append("$CSV_COLUMNS\n")

        val timestamp = metadata.exportedAt
        for (spot in spots) {
            for (m in spot.measurements) {
                val normBssid = BssidNormalizer.normalizeBssid(m.bssid)
                val cleanSsid = "Anon-AP"
                val rawStr = "\"[" + m.rawReadings.joinToString(";") + "]\""
                sb.append("$timestamp,${spot.spotLabel},${spot.coordX},${spot.coordY},$normBssid,$cleanSsid,${m.rawReadings.size},$rawStr,${m.avgRssi}\n")
            }
        }
        return sb.toString()
    }

    /**
     * Parses a string containing fingerprint data.
     * Supports both modern files with # metadata headers and legacy raw CSV files.
     */
    fun parse(content: String): Pair<FingerprintFileMetadata, List<RawSurveySpot>> {
        var version = 1
        var deviceId = "unknown"
        var model = "Unknown Device"
        var sdk = 0
        var exportedAt = System.currentTimeMillis()
        var calibA = 1.0
        var calibB = 0.0

        val spotMeasurements = mutableMapOf<String, MutableMap<String, RawApMeasurement>>()
        val spotMaxScans = mutableMapOf<String, Int>()
        val spotCoords = mutableMapOf<String, Pair<Double, Double>>()

        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            if (trimmed.startsWith("#")) {
                when {
                    trimmed.startsWith(HEADER_VERSION) ->
                        version = trimmed.removePrefix(HEADER_VERSION).toIntOrNull() ?: 1
                    trimmed.startsWith(HEADER_DEVICE_ID) ->
                        deviceId = trimmed.removePrefix(HEADER_DEVICE_ID)
                    trimmed.startsWith(HEADER_MODEL) ->
                        model = trimmed.removePrefix(HEADER_MODEL)
                    trimmed.startsWith(HEADER_SDK) ->
                        sdk = trimmed.removePrefix(HEADER_SDK).toIntOrNull() ?: 0
                    trimmed.startsWith(HEADER_EXPORTED_AT) ->
                        exportedAt = trimmed.removePrefix(HEADER_EXPORTED_AT).toLongOrNull() ?: exportedAt
                    trimmed.startsWith(HEADER_CALIB_A) ->
                        calibA = trimmed.removePrefix(HEADER_CALIB_A).toDoubleOrNull() ?: 1.0
                    trimmed.startsWith(HEADER_CALIB_B) ->
                        calibB = trimmed.removePrefix(HEADER_CALIB_B).toDoubleOrNull() ?: 0.0
                }
                return@forEach
            }

            if (trimmed.startsWith("timestamp")) {
                return@forEach
            }

            // Data line processing
            if (trimmed.contains("\"[")) {
                val parts = trimmed.split("\"")
                val meta = parts[0].split(",")
                val hasCoords = meta.size >= 7 && meta[2].toDoubleOrNull() != null && meta[3].toDoubleOrNull() != null
                val spot = meta.getOrNull(1)?.trim() ?: ""
                val coordX = if (hasCoords) meta.getOrNull(2)?.trim()?.toDoubleOrNull() ?: 0.0 else 0.0
                val coordY = if (hasCoords) meta.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 0.0 else 0.0
                val rawBssid = if (hasCoords) meta.getOrNull(4)?.trim() ?: "" else meta.getOrNull(2)?.trim() ?: ""
                val rawSsid = if (hasCoords) meta.getOrNull(5)?.trim() ?: "Unknown" else meta.getOrNull(3)?.trim() ?: "Unknown"
                val count = if (hasCoords) meta.getOrNull(6)?.trim()?.toIntOrNull() ?: 1 else meta.getOrNull(4)?.trim()?.toIntOrNull() ?: 1

                val rawList = if (parts.size > 1) {
                    parts[1].trim(' ', '[', ']')
                        .split(";")
                        .mapNotNull { it.toIntOrNull() }
                } else emptyList()

                val avg = parts.getOrNull(2)?.trim(',', ' ')?.toIntOrNull()
                    ?: (if (rawList.isNotEmpty()) rawList.average().roundToInt() else -100)

                if (spot.isNotEmpty() && rawBssid.isNotEmpty()) {
                    val normBssid = BssidNormalizer.normalizeBssid(rawBssid)
                    val currentMax = spotMaxScans[spot] ?: 0
                    spotMaxScans[spot] = maxOf(currentMax, count, rawList.size)
                    if (hasCoords || !spotCoords.containsKey(spot)) {
                        spotCoords[spot] = Pair(coordX, coordY)
                    }
                    spotMeasurements.getOrPut(spot) { mutableMapOf() }[normBssid] =
                        RawApMeasurement(normBssid, rawSsid, rawList.ifEmpty { listOf(avg) }, avg)
                }
            } else {
                // Legacy 5-column format: timestamp,spot_label,bssid,ssid,avg_rssi
                val parts = trimmed.split(",")
                if (parts.size >= 5) {
                    val spot = parts[1].trim()
                    val rawBssid = parts[2].trim()
                    val rawSsid = parts[3].trim()
                    val rssi = parts.last().trim().toIntOrNull() ?: -100

                    if (spot.isNotEmpty() && rawBssid.isNotEmpty()) {
                        val normBssid = BssidNormalizer.normalizeBssid(rawBssid)
                        val currentMax = spotMaxScans[spot] ?: 0
                        spotMaxScans[spot] = maxOf(currentMax, 1)
                        spotMeasurements.getOrPut(spot) { mutableMapOf() }[normBssid] =
                            RawApMeasurement(normBssid, rawSsid, listOf(rssi), rssi)
                    }
                }
            }
        }

        if (spotMeasurements.isEmpty()) {
            throw IllegalArgumentException("No valid survey data found in content")
        }

        val spots = spotMeasurements.map { (spot, bssidMap) ->
            val totalScans = spotMaxScans[spot] ?: 1
            val coords = spotCoords[spot] ?: Pair(0.0, 0.0)
            RawSurveySpot(spot, bssidMap.values.toList(), totalScans, coords.first, coords.second)
        }

        val metadata = FingerprintFileMetadata(
            version = version,
            deviceId = deviceId,
            model = model,
            sdk = sdk,
            exportedAt = exportedAt,
            calibA = calibA,
            calibB = calibB
        )

        return Pair(metadata, spots)
    }
}
