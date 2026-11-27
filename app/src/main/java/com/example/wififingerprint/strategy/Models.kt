package com.example.wififingerprint.strategy

/**
 * Raw AP measurement from a single survey session.
 */
data class RawApMeasurement(
    val bssid: String,
    val ssid: String,
    val rawReadings: List<Int>,
    val avgRssi: Int
)

/**
 * Immutable survey record for a single physical spot, containing all raw readings collected over N scans.
 */
data class RawSurveySpot(
    val spotLabel: String,
    val measurements: List<RawApMeasurement>,
    val totalScans: Int,
    val coordX: Double = 0.0,
    val coordY: Double = 0.0
) {
    // Quick helper mapping bssid -> list of raw readings
    val bssidToRawMap: Map<String, List<Int>> by lazy {
        measurements.associate { it.bssid to it.rawReadings }
    }

    // Quick helper mapping bssid -> average RSSI
    val bssidToAvgMap: Map<String, Int> by lazy {
        measurements.associate { it.bssid to it.avgRssi }
    }
}

/**
 * Represents the live online query scan vector across visible APs.
 */
data class LiveQueryScan(
    val readings: Map<String, Int>,
    val bssidToSsidMap: Map<String, String> = emptyMap()
)

/**
 * Individual spot ranking entry.
 */
data class SpotRanking(
    val rank: Int,
    val spotLabel: String,
    val scoreMetric: Double,
    val metricLabel: String,
    val details: String = ""
)

/**
 * Comprehensive localization outcome produced by any strategy.
 */
data class LocalizationResult(
    val strategyName: String,
    val predictedSpot: String?,
    val confidenceMargin: Double?,
    val rankings: List<SpotRanking>,
    val logOutput: String
)

/**
 * Strategy mode selector for user settings.
 */
enum class StrategyMode(val key: String, val title: String) {
    HYBRID("HYBRID", "Smart Hybrid (WKNN + Mamdani FIS)"),
    KNN("KNN", "Strategy 1: k-NN Only"),
    FUZZY_MATRIX("FUZZY_MATRIX", "Strategy 2: Fuzzy 3D Matrix Only"),
    PATTERN("PATTERN", "Strategy 3: Cross-Device Pattern Only"),
    MAMDANI("MAMDANI", "Strategy 4: Mamdani Fuzzy System (FIS)"),
    BOTH("BOTH", "k-NN vs Fuzzy Matrix"),
    ALL("ALL", "All 4 Strategies Comparison");

    companion object {
        fun fromKey(key: String?): StrategyMode {
            return entries.firstOrNull { it.key == key } ?: HYBRID
        }
    }
}

/**
 * Active Wi-Fi scan collection mode.
 */
enum class ScanMode {
    SURVEY,
    LOCATE,
    CALIBRATE,
    EXPERIMENT
}
