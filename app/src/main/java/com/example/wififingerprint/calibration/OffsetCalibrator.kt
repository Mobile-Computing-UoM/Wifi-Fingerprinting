package com.example.wififingerprint.calibration

import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.RawSurveySpot
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PerApDiff(
    val bssid: String,
    val liveRssi: Int,
    val refRssi: Int,
    val diffDb: Int // liveRssi - refRssi (positive means live phone antenna is stronger)
)

data class CalibrationResult(
    val spotLabel: String,
    val medianOffsetDb: Double,
    val meanOffsetDb: Double,
    val stdDevDb: Double,
    val sharedApCount: Int,
    val perApDiffs: List<PerApDiff>,
    val isValid: Boolean,
    val warningMessage: String? = null
)

object OffsetCalibrator {

    /**
     * Calculates the antenna offset between a live survey on this phone and a reference spot.
     * Uses median difference to reject outlier APs (e.g. temporary fading or obstacles).
     */
    fun compute(
        liveScan: Map<String, Int>,
        referenceSpot: RawSurveySpot,
        minSharedAps: Int = 3
    ): CalibrationResult {
        val refMap = referenceSpot.bssidToAvgMap
        val diffs = mutableListOf<PerApDiff>()

        for ((bssid, liveVal) in liveScan) {
            val refVal = refMap[bssid]
            if (refVal != null) {
                diffs.add(PerApDiff(bssid, liveVal, refVal, liveVal - refVal))
            }
        }

        if (diffs.size < minSharedAps) {
            return CalibrationResult(
                spotLabel = referenceSpot.spotLabel,
                medianOffsetDb = 0.0,
                meanOffsetDb = 0.0,
                stdDevDb = 0.0,
                sharedApCount = diffs.size,
                perApDiffs = diffs,
                isValid = false,
                warningMessage = "Only ${diffs.size} shared APs found (minimum required: $minSharedAps)."
            )
        }

        val rawDiffs = diffs.map { it.diffDb.toDouble() }.sorted()
        val median = if (rawDiffs.size % 2 == 1) {
            rawDiffs[rawDiffs.size / 2]
        } else {
            (rawDiffs[rawDiffs.size / 2 - 1] + rawDiffs[rawDiffs.size / 2]) / 2.0
        }

        val mean = rawDiffs.average()
        val variance = rawDiffs.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)

        var warning: String? = null
        if (stdDev > 6.0) {
            warning = "High discrepancy between APs (spread = %.1f dB). Verify you are standing at '%s'.".format(
                Locale.US, stdDev, referenceSpot.spotLabel
            )
        }

        return CalibrationResult(
            spotLabel = referenceSpot.spotLabel,
            medianOffsetDb = median,
            meanOffsetDb = mean,
            stdDevDb = stdDev,
            sharedApCount = diffs.size,
            perApDiffs = diffs,
            isValid = true,
            warningMessage = warning
        )
    }

    /**
     * Applies the estimated calibration offset to a live query scan:
     * corrected_rssi = live_rssi - offset
     * Subtracts the antenna sensitivity offset to project signals onto the reference phone's scale.
     */
    fun apply(
        query: LiveQueryScan,
        offsetDb: Double
    ): LiveQueryScan {
        val roundedOffset = offsetDb.roundToInt()
        val correctedReadings = query.readings.mapValues { (_, liveVal) ->
            liveVal - roundedOffset
        }
        return LiveQueryScan(correctedReadings, query.bssidToSsidMap)
    }
}
