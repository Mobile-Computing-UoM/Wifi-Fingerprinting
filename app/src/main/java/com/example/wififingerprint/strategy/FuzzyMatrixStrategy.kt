package com.example.wififingerprint.strategy

/**
 * Discrete Fuzzy Distribution cell for a single (Spot, AP) pair.
 * Encapsulates the discrete empirical frequency distribution over RSSI dBm values
 * and the explicit Not Seen (NS) condition.
 */
data class DiscreteFuzzyCell(
    val bssid: String,
    val rssiFrequencies: Map<Int, Double>,
    val notSeenFrequency: Double
) {
    /**
     * Look up membership grade μ(v) in [0.0, 1.0].
     * @param observedRssi The measured RSSI integer, or null if the AP was not heard (NS).
     */
    fun getMembership(observedRssi: Int?): Double {
        return if (observedRssi == null) {
            notSeenFrequency
        } else {
            rssiFrequencies[observedRssi] ?: 0.0
        }
    }
}

/**
 * Strategy 2: Fuzzy Discrete 3D Representation with Robust Average Inference.
 *
 * Spans 3 Dimensions:
 * - Dimension 1: Spots (S)
 * - Dimension 2: AP BSSIDs (A)
 * - Dimension 3: Signal Values (v in dBm or NS)
 *
 * Inference computes the arithmetic average of membership grades across all known APs.
 * Tolerates deep multipath fades and unseen readings without collapsing to zero.
 */
class FuzzyMatrixStrategy : LocalizationStrategy {
    override val name: String = "Strategy 2: Fuzzy 3D Matrix (Robust Average)"
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

        // 2. Build the 3D discrete frequency cells for every (Spot, AP) combination
        for (spot in surveys) {
            val spotMap = mutableMapOf<String, DiscreteFuzzyCell>()
            val totalScans = if (spot.totalScans > 0) spot.totalScans else 1
            val measurementByBssid = spot.measurements.associateBy { it.bssid }

            for (bssid in allKnownAps) {
                val measurement = measurementByBssid[bssid]
                if (measurement != null && measurement.rawReadings.isNotEmpty()) {
                    val raw = measurement.rawReadings
                    val heardCount = raw.size

                    // Compute empirical PMF for each observed discrete dBm integer
                    val freqMap = raw.groupingBy { it }
                        .eachCount()
                        .mapValues { (_, count) -> count.toDouble() / totalScans }

                    // Not Seen (NS) frequency: fraction of total scans where this AP was not heard
                    val missingCount = (totalScans - heardCount).coerceAtLeast(0)
                    val nsFreq = missingCount.toDouble() / totalScans

                    spotMap[bssid] = DiscreteFuzzyCell(bssid, freqMap, nsFreq)
                } else {
                    // AP was never seen at this spot during the survey: μ(NS) = 1.0, everything else = 0.0
                    spotMap[bssid] = DiscreteFuzzyCell(bssid, emptyMap(), 1.0)
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
        log.append(" fuzzy [$name]\n")
        log.append("========================================\n")
        log.append("Evaluating query against ${matrix.size} spots across ${allKnownAps.size} total known AP dimensions:\n\n")

        val totalAps = allKnownAps.size
        // Triple(SpotLabel, AverageMembership, Details)
        val spotEvaluations = mutableListOf<Triple<String, Double, String>>()

        for ((spotLabel, cells) in matrix) {
            var sumMembership = 0.0
            val apDetails = StringBuilder()

            for (bssid in allKnownAps) {
                val cell = cells[bssid]
                val observedRssi = query.readings[bssid] // null if not heard
                val mu = cell?.getMembership(observedRssi) ?: 0.0
                sumMembership += mu

                val ssid = apSsidMap[bssid] ?: query.bssidToSsidMap[bssid] ?: bssid.takeLast(5)
                val readStr = if (observedRssi != null) "$observedRssi dBm" else "NS"
                apDetails.append("  • $ssid ($readStr) -> μ = ${"%.3f".format(mu)}\n")
            }

            val avgMembership = sumMembership / totalAps
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
