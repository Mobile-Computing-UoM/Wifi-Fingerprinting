package com.example.wififingerprint.strategy

import kotlin.math.abs

/**
 * Non-parametric Fuzzy Distribution cell for a single (Spot, AP) pair.
 * Preserves the empirical distribution of surveyed RSSI samples directly without
 * collapsing them into a parametric mean (Gaussian), thereby retaining empirical
 * multimodality, skewness, and multipath characteristics.
 *
 * Each surveyed reading contributes a triangular fuzzy membership kernel:
 *   K(v, r_k) = max(0.0, 1.0 - |v - r_k| / Δ)
 *
 * Bandwidth Δ is the fuzzy tolerance width (default 3.5 dBm, matching mobile RF noise).
 */
data class DiscreteFuzzyCell(
    val bssid: String,
    val rawSamples: List<Int>,
    val totalSurveyScans: Int,
    val notSeenFrequency: Double,
    val toleranceBandwidth: Double = 3.5
) {
    /**
     * Compute empirical membership grade μ(v) ∈ [0.0, 1.0].
     * @param observedRssi The live measured RSSI integer, or null if the AP was not heard (NS).
     */
    fun getMembership(observedRssi: Int?): Double {
        if (observedRssi == null) {
            return notSeenFrequency
        }
        if (rawSamples.isEmpty()) {
            return 0.0
        }
        val totalScans = if (totalSurveyScans > 0) totalSurveyScans else rawSamples.size
        var kernelSum = 0.0
        for (r in rawSamples) {
            val dist = abs(observedRssi - r)
            if (dist < toleranceBandwidth) {
                kernelSum += (1.0 - dist / toleranceBandwidth)
            }
        }
        return (kernelSum / totalScans).coerceIn(0.0, 1.0)
    }
}

/**
 * Strategy 2: Non-Parametric Empirical Fuzzy Distribution with Multi-AP Fusion.
 *
 * Spans 3 Dimensions:
 * - Dimension 1: Candidate Spatial Spots (S)
 * - Dimension 2: AP BSSIDs (A)
 * - Dimension 3: Empirical Signal Distributions (v in dBm or NS)
 *
 * Unlike parametric Gaussian methods (which collapse samples to a mean and standard deviation,
 * effectively acting as a k-NN Euclidean distance surrogate), this strategy evaluates live readings
 * directly against the non-parametric empirical sample set using continuous fuzzy membership kernels.
 *
 * It prevents the "discrete 0.0 cliff" when integer values shift by 1 dBm, while preserving
 * empirical multimodal peaks caused by multipath fading.
 */
class FuzzyMatrixStrategy(
    private val toleranceBandwidth: Double = 3.5
) : LocalizationStrategy {
    override val name: String = "Strategy 2: Non-Parametric Fuzzy 3D Matrix"
    override val shortCode: String = "FuzzyMatrix"

    // Set of all distinct AP BSSIDs known across all survey locations
    private val allKnownAps = mutableSetOf<String>()
    private val apSsidMap = mutableMapOf<String, String>()

    // 3D Grid: SpotLabel -> (BSSID -> DiscreteFuzzyCell)
    private val matrix = mutableMapOf<String, Map<String, DiscreteFuzzyCell>>()
    private val spotTotalScans = mutableMapOf<String, Int>()

    override fun buildModel(surveys: List<RawSurveySpot>) {
        allKnownAps.clear()
        apSsidMap.clear()
        matrix.clear()
        spotTotalScans.clear()

        // 1. Gather all unique APs across the entire survey environment
        for (spot in surveys) {
            spotTotalScans[spot.spotLabel] = spot.totalScans
            for (m in spot.measurements) {
                allKnownAps.add(m.bssid)
                if (m.ssid.isNotBlank() && m.ssid != "Unknown") {
                    apSsidMap[m.bssid] = m.ssid
                }
            }
        }

        // 2. Build non-parametric empirical fuzzy cells for every (Spot, AP) combination
        for (spot in surveys) {
            val spotMap = mutableMapOf<String, DiscreteFuzzyCell>()
            val totalScans = if (spot.totalScans > 0) spot.totalScans else 1
            val measurementByBssid = spot.measurements.associateBy { it.bssid }

            for (bssid in allKnownAps) {
                val measurement = measurementByBssid[bssid]
                if (measurement != null && measurement.rawReadings.isNotEmpty()) {
                    val raw = measurement.rawReadings
                    val heardCount = raw.size

                    // Not Seen (NS) frequency: fraction of total scans where this AP was not heard
                    val missingCount = (totalScans - heardCount).coerceAtLeast(0)
                    val nsFreq = missingCount.toDouble() / totalScans

                    spotMap[bssid] = DiscreteFuzzyCell(
                        bssid = bssid,
                        rawSamples = raw,
                        totalSurveyScans = totalScans,
                        notSeenFrequency = nsFreq,
                        toleranceBandwidth = toleranceBandwidth
                    )
                } else {
                    // AP was never seen at this spot during survey: μ(NS) = 1.0, any observed dBm = 0.0
                    spotMap[bssid] = DiscreteFuzzyCell(
                        bssid = bssid,
                        rawSamples = emptyList(),
                        totalSurveyScans = totalScans,
                        notSeenFrequency = 1.0,
                        toleranceBandwidth = toleranceBandwidth
                    )
                }
            }
            matrix[spot.spotLabel] = spotMap
        }
    }

    override fun infer(query: LiveQueryScan): LocalizationResult {
        if (matrix.isEmpty() || allKnownAps.isEmpty()) {
            return LocalizationResult(
                strategyName = name,
                predictedSpot = null,
                confidenceMargin = null,
                rankings = emptyList(),
                logOutput = "⚠️ No survey model built! Record survey spots first.\n"
            )
        }

        val log = StringBuilder()
        log.append("\n========================================\n")
        log.append(" [$shortCode] Non-Parametric Fuzzy Inference\n")
        log.append("========================================\n")
        log.append("Live query heard ${query.readings.size} APs against ${matrix.size} candidate spots (Δ = $toleranceBandwidth dBm):\n\n")

        // Triple(SpotLabel, AverageMembership, Details)
        val spotEvaluations = mutableListOf<Triple<String, Double, String>>()

        for ((spotLabel, cells) in matrix) {
            var sumMembership = 0.0
            val apDetails = StringBuilder()

            // Filter relevant APs: evaluate APs that were either heard in the query OR heard at this candidate spot.
            // This prevents building-wide unobserved distant APs from artificially inflating scores with false 1.0 matches.
            val relevantAps = allKnownAps.filter { bssid ->
                query.readings.containsKey(bssid) || (cells[bssid]?.rawSamples?.isNotEmpty() == true)
            }
            val evalAps = if (relevantAps.isNotEmpty()) relevantAps else allKnownAps.toList()

            for (bssid in evalAps) {
                val cell = cells[bssid]
                val observedRssi = query.readings[bssid] // null if not heard
                val mu = cell?.getMembership(observedRssi) ?: 0.0
                sumMembership += mu

                val ssid = apSsidMap[bssid] ?: query.bssidToSsidMap[bssid] ?: bssid.takeLast(5)
                val readStr = if (observedRssi != null) "$observedRssi dBm" else "NS"
                apDetails.append("  • $ssid ($readStr) -> μ = ${"%.3f".format(mu)}\n")
            }

            val avgMembership = sumMembership / evalAps.size
            spotEvaluations.add(Triple(spotLabel, avgMembership, apDetails.toString()))
        }

        // Rank descending by average membership
        spotEvaluations.sortByDescending { it.second }

        val rankings = mutableListOf<SpotRanking>()
        for ((idx, item) in spotEvaluations.withIndex()) {
            val rank = idx + 1
            val marker = if (rank == 1) "🏆 [WINNER]" else "   "
            val avgStr = "%.4f".format(item.second)
            log.append("$marker #$rank ${item.first} -> Average Membership: $avgStr\n")
            rankings.add(SpotRanking(rank, item.first, item.second, "Avg Membership", "Score: $avgStr"))
        }

        val winner = spotEvaluations.first().first
        val bestAvg = spotEvaluations.first().second

        val margin = if (spotEvaluations.size > 1) {
            val secondAvg = spotEvaluations[1].second
            bestAvg - secondAvg
        } else {
            null
        }

        val marginStr = if (margin != null) " (Winning Margin: +${"%.4f".format(margin)})" else ""
        log.append("\n📍 PREDICTED LOCATION ($shortCode): $winner (Avg: ${"%.4f".format(bestAvg)})$marginStr\n")

        // Detailed breakdown of winning spot's AP memberships
        log.append("\nBreakdown of Winning Spot ($winner):\n")
        log.append(spotEvaluations.first().third)

        return LocalizationResult(
            strategyName = name,
            predictedSpot = winner,
            confidenceMargin = margin,
            rankings = rankings,
            logOutput = log.toString()
        )
    }
}
