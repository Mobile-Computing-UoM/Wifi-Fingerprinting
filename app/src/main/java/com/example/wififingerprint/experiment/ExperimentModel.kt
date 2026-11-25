package com.example.wififingerprint.experiment

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Configuration parameters for an automated multi-iteration positioning experiment.
 */
data class ExperimentConfig(
    val separationMeters: Double = 2.0,
    val actualSpot: String = "S1",
    val actualX: Double = 0.0,
    val actualY: Double = 0.0,
    val totalTrials: Int = 20,
    val locateScansPerTrial: Int = 10
)

/**
 * Represents the evaluation outcome of a single trial iteration across all 4 strategies
 * (k-NN, Fuzzy 3D Matrix, Cross-Device Pattern, and Mamdani FIS).
 */
data class ExperimentIterationResult(
    val timestampMs: Long,
    val isoTime: String,
    val separationMeters: Double,
    val trialIndex: Int,
    val totalTrials: Int,
    val locateScans: Int,
    val actualSpot: String,
    val actualX: Double,
    val actualY: Double,
    val knnPredSpot: String,
    val knnPredX: Double,
    val knnPredY: Double,
    val knnErrorMeters: Double,
    val knnCorrect: Boolean,
    val fuzzyPredSpot: String,
    val fuzzyPredX: Double,
    val fuzzyPredY: Double,
    val fuzzyErrorMeters: Double,
    val fuzzyCorrect: Boolean,
    val rawSignals: String,
    val rawCsvLine: String = "",
    val patternPredSpot: String = "",
    val patternPredX: Double = 0.0,
    val patternPredY: Double = 0.0,
    val patternErrorMeters: Double = 0.0,
    val patternCorrect: Boolean = false,
    val mamdaniPredSpot: String = "",
    val mamdaniPredX: Double = 0.0,
    val mamdaniPredY: Double = 0.0,
    val mamdaniErrorMeters: Double = 0.0,
    val mamdaniCorrect: Boolean = false
) {
    companion object {
        fun create(
            separationMeters: Double,
            trialIndex: Int,
            totalTrials: Int,
            locateScans: Int,
            actualSpot: String,
            actualX: Double,
            actualY: Double,
            knnPredSpot: String,
            knnPredX: Double,
            knnPredY: Double,
            knnErrorMeters: Double,
            fuzzyPredSpot: String,
            fuzzyPredX: Double,
            fuzzyPredY: Double,
            fuzzyErrorMeters: Double,
            rawSignalsMap: Map<String, Int>,
            patternPredSpot: String = "",
            patternPredX: Double = 0.0,
            patternPredY: Double = 0.0,
            patternErrorMeters: Double = 0.0,
            mamdaniPredSpot: String = "",
            mamdaniPredX: Double = 0.0,
            mamdaniPredY: Double = 0.0,
            mamdaniErrorMeters: Double = 0.0
        ): ExperimentIterationResult {
            val ts = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val iso = sdf.format(Date(ts))
            val knnCorr = actualSpot.trim().equals(knnPredSpot.trim(), ignoreCase = true)
            val fuzzyCorr = actualSpot.trim().equals(fuzzyPredSpot.trim(), ignoreCase = true)
            val patternCorr = patternPredSpot.isNotBlank() && actualSpot.trim().equals(patternPredSpot.trim(), ignoreCase = true)
            val mamdaniCorr = mamdaniPredSpot.isNotBlank() && actualSpot.trim().equals(mamdaniPredSpot.trim(), ignoreCase = true)
            val sigString = rawSignalsMap.entries.joinToString(";") { "${it.key}=${it.value}" }

            val csvLine = StringBuilder().apply {
                append("$ts,")
                append("\"$iso\",")
                append("${"%.2f".format(Locale.US, separationMeters)},")
                append("$trialIndex,")
                append("$totalTrials,")
                append("$locateScans,")
                append("\"$actualSpot\",")
                append("${"%.2f".format(Locale.US, actualX)},")
                append("${"%.2f".format(Locale.US, actualY)},")
                append("\"$knnPredSpot\",")
                append("${"%.2f".format(Locale.US, knnPredX)},")
                append("${"%.2f".format(Locale.US, knnPredY)},")
                append("${"%.3f".format(Locale.US, knnErrorMeters)},")
                append("${if (knnCorr) 1 else 0},")
                append("\"$fuzzyPredSpot\",")
                append("${"%.2f".format(Locale.US, fuzzyPredX)},")
                append("${"%.2f".format(Locale.US, fuzzyPredY)},")
                append("${"%.3f".format(Locale.US, fuzzyErrorMeters)},")
                append("${if (fuzzyCorr) 1 else 0},")
                append("\"$sigString\",")
                append("\"$patternPredSpot\",")
                append("${"%.2f".format(Locale.US, patternPredX)},")
                append("${"%.2f".format(Locale.US, patternPredY)},")
                append("${"%.3f".format(Locale.US, patternErrorMeters)},")
                append("${if (patternCorr) 1 else 0},")
                append("\"$mamdaniPredSpot\",")
                append("${"%.2f".format(Locale.US, mamdaniPredX)},")
                append("${"%.2f".format(Locale.US, mamdaniPredY)},")
                append("${"%.3f".format(Locale.US, mamdaniErrorMeters)},")
                append("${if (mamdaniCorr) 1 else 0}")
            }.toString()

            return ExperimentIterationResult(
                timestampMs = ts,
                isoTime = iso,
                separationMeters = separationMeters,
                trialIndex = trialIndex,
                totalTrials = totalTrials,
                locateScans = locateScans,
                actualSpot = actualSpot,
                actualX = actualX,
                actualY = actualY,
                knnPredSpot = knnPredSpot,
                knnPredX = knnPredX,
                knnPredY = knnPredY,
                knnErrorMeters = knnErrorMeters,
                knnCorrect = knnCorr,
                fuzzyPredSpot = fuzzyPredSpot,
                fuzzyPredX = fuzzyPredX,
                fuzzyPredY = fuzzyPredY,
                fuzzyErrorMeters = fuzzyErrorMeters,
                fuzzyCorrect = fuzzyCorr,
                rawSignals = sigString,
                rawCsvLine = csvLine,
                patternPredSpot = patternPredSpot,
                patternPredX = patternPredX,
                patternPredY = patternPredY,
                patternErrorMeters = patternErrorMeters,
                patternCorrect = patternCorr,
                mamdaniPredSpot = mamdaniPredSpot,
                mamdaniPredX = mamdaniPredX,
                mamdaniPredY = mamdaniPredY,
                mamdaniErrorMeters = mamdaniErrorMeters,
                mamdaniCorrect = mamdaniCorr
            )
        }
    }
}

/**
 * Aggregated summary statistics for a batch experiment run across all 4 strategies.
 */
data class ExperimentSummaryStats(
    val separationMeters: Double,
    val actualSpot: String,
    val completedTrials: Int,
    val targetTrials: Int,
    val knnCorrectCount: Int,
    val knnAccuracyPct: Double,
    val knnAvgErrorMeters: Double,
    val fuzzyCorrectCount: Int,
    val fuzzyAccuracyPct: Double,
    val fuzzyAvgErrorMeters: Double,
    val patternCorrectCount: Int = 0,
    val patternAccuracyPct: Double = 0.0,
    val patternAvgErrorMeters: Double = 0.0,
    val mamdaniCorrectCount: Int = 0,
    val mamdaniAccuracyPct: Double = 0.0,
    val mamdaniAvgErrorMeters: Double = 0.0
)
