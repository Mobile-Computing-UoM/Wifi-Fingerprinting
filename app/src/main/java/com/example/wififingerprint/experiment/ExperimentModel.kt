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
 * Represents the evaluation outcome of a single trial iteration.
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
    val rawCsvLine: String = ""
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
            rawSignalsMap: Map<String, Int>
        ): ExperimentIterationResult {
            val ts = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val iso = sdf.format(Date(ts))
            val knnCorr = actualSpot.trim().equals(knnPredSpot.trim(), ignoreCase = true)
            val fuzzyCorr = actualSpot.trim().equals(fuzzyPredSpot.trim(), ignoreCase = true)
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
                append("\"$sigString\"")
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
                rawCsvLine = csvLine
            )
        }
    }
}

/**
 * Aggregated summary statistics for a batch experiment run.
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
    val fuzzyAvgErrorMeters: Double
)

