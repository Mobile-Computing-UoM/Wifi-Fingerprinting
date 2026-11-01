package com.example.wififingerprint.strategy

import java.util.Locale

/**
 * Strategy 3: Device-Independent Pattern Matching.
 *
 * Designed specifically for cross-device localization (e.g. Survey on Oppo A54s,
 * locate on Samsung A20e / LineageOS).
 *
 * Utilizes pairwise signal differences, mean-centered relative RSSI, and rank ordering
 * to eliminate antenna sensitivity and hardware bias without requiring upfront calibration.
 */
class PatternStrategy(
    private val minSharedAps: Int = 2
) : LocalizationStrategy {

    override val name: String = "Strategy 3: Cross-Device Pattern"
    override val shortCode: String = "Pattern"

    private var spots: List<RawSurveySpot> = emptyList()

    override fun buildModel(surveys: List<RawSurveySpot>) {
        this.spots = surveys
    }

    fun isModelLoaded(): Boolean = spots.isNotEmpty()

    override fun infer(query: LiveQueryScan): LocalizationResult {
        if (spots.isEmpty()) {
            return LocalizationResult(
                strategyName = name,
                predictedSpot = null,
                confidenceMargin = null,
                rankings = emptyList(),
                logOutput = "❌ Pattern Strategy model is empty. Load or import a survey database first."
            )
        }

        val rankingList = mutableListOf<SpotRanking>()
        val debugLines = mutableListOf<String>()
        debugLines.add("--- Cross-Device Pattern Matching ---")
        debugLines.add("Query APs count: ${query.readings.size}")

        for (spot in spots) {
            val (score, details) = PatternFeatures.compositeDistance(
                queryReadings = query.readings,
                spot = spot,
                minSharedAps = minSharedAps
            )
            val spotLabel = spot.spotLabel

            rankingList.add(
                SpotRanking(
                    rank = 0, // Assigned after sorting
                    spotLabel = spotLabel,
                    scoreMetric = score,
                    metricLabel = "Pattern Error (dB)",
                    details = details
                )
            )
        }

        // Sort ascending: lower distance/error indicates a closer pattern match
        rankingList.sortBy { it.scoreMetric }

        // Re-assign ranks 1..N
        val sortedRankings = rankingList.mapIndexed { index, item ->
            item.copy(rank = index + 1)
        }

        val top1 = sortedRankings.firstOrNull()
        val top2 = sortedRankings.getOrNull(1)

        val predictedSpot = if (top1 != null && top1.scoreMetric < Double.MAX_VALUE) {
            top1.spotLabel
        } else {
            null
        }

        val confidenceMargin = if (top1 != null && top2 != null && top1.scoreMetric < Double.MAX_VALUE) {
            top2.scoreMetric - top1.scoreMetric
        } else null

        for (item in sortedRankings.take(5)) {
            val formattedScore = if (item.scoreMetric == Double.MAX_VALUE) "N/A" else "%.2f".format(Locale.US, item.scoreMetric)
            debugLines.add("#${item.rank} ${item.spotLabel}: error=$formattedScore (${item.details})")
        }

        if (confidenceMargin != null) {
            debugLines.add("Separation Margin: %.2f dB".format(Locale.US, confidenceMargin))
        }

        return LocalizationResult(
            strategyName = name,
            predictedSpot = predictedSpot,
            confidenceMargin = confidenceMargin,
            rankings = sortedRankings,
            logOutput = debugLines.joinToString("\n")
        )
    }
}
