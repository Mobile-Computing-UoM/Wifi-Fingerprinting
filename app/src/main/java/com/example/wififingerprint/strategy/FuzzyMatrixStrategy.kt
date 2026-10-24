package com.example.wififingerprint.strategy

import kotlin.math.max

/**
 * Discrete Fuzzy Distribution cell for a single (Spot, AP) pair.
 * Encapsulates the discrete empirical frequency distribution over RSSI dBm values
 * and the explicit Not Seen (NS) condition.
 *
 * Supports Neighbour Smoothing:
 * If a reading falls 1 dB off a stored value, exact lookup scores 0.
 * Smoothing lets a stored value pass 50% of its membership to its ±1 dB neighbours
 * and 25% to its ±2 dB neighbours (taking the maximum).
 */
data class DiscreteFuzzyCell(
    val bssid: String,
    val rssiFrequencies: Map<Int, Double>,
    val notSeenFrequency: Double
) {
    /**
     * Look up exact raw discrete membership grade μ(v) in [0.0, 1.0].
     * Direct exact integer lookup without smoothing.
     */
    fun getRawMembership(observedRssi: Int?): Double {
        return if (observedRssi == null) {
            notSeenFrequency
        } else {
            rssiFrequencies[observedRssi] ?: 0.0
        }
    }

    /**
     * Compute membership grade with Neighbour Smoothing:
     * - Exact match: 100% of stored raw membership
     * - ±1 dB neighbour: 50% of neighbour's stored membership
     * - ±2 dB neighbour: 25% of neighbour's stored membership
     * Takes the maximum across exact, ±1 dB, and ±2 dB candidates:
     *   μ_smoothed(v) = max(μ(v), 0.50 * max(μ(v-1), μ(v+1)), 0.25 * max(μ(v-2), μ(v+2)))
     */
    fun getSmoothedMembership(observedRssi: Int?): Double {
        if (observedRssi == null) {
            return notSeenFrequency
        }
        val exact = rssiFrequencies[observedRssi] ?: 0.0
        val dist1 = max(
            rssiFrequencies[observedRssi - 1] ?: 0.0,
            rssiFrequencies[observedRssi + 1] ?: 0.0
        ) * 0.50
        val dist2 = max(
            rssiFrequencies[observedRssi - 2] ?: 0.0,
            rssiFrequencies[observedRssi + 2] ?: 0.0
        ) * 0.25

        return max(exact, max(dist1, dist2))
    }

    /**
     * Default membership lookup (uses Neighbour Smoothing).
     */
    fun getMembership(observedRssi: Int?): Double = getSmoothedMembership(observedRssi)
}

/**
 * Strategy 2: Discrete Fuzzy 3D Matrix with Neighbour Smoothing.
 *
 * Spans 3 Dimensions:
 * - Dimension 1: Candidate Spatial Spots (S)
 * - Dimension 2: AP BSSIDs (A)
 * - Dimension 3: Signal Values (v in dBm or NS)
 *
 * Resolves the discrete 0.0 cliff via Neighbour Smoothing:
 * - Passes 50% membership to ±1 dB neighbours
 * - Passes 25% membership to ±2 dB neighbours (taking max)
 * Preserves the true non-parametric empirical shape, bimodal peaks, and skewness
 * without collapsing data into an artificial unimodal Gaussian mean.
 */
class FuzzyMatrixStrategy(
    val useNeighbourSmoothing: Boolean = true
) : LocalizationStrategy {
    override val name: String = "Strategy 2: Fuzzy 3D Matrix (Neighbour Smoothing)"
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

        // 2. Build the discrete frequency cells for every (Spot, AP) combination
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
        log.append(" [$shortCode] Fuzzy Neighbour-Smoothed Inference\n")
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
                val mu = if (useNeighbourSmoothing) {
                    cell?.getSmoothedMembership(observedRssi) ?: 0.0
                } else {
                    cell?.getRawMembership(observedRssi) ?: 0.0
                }
                val rawMu = cell?.getRawMembership(observedRssi) ?: 0.0
                sumMembership += mu

                val ssid = apSsidMap[bssid] ?: query.bssidToSsidMap[bssid] ?: bssid.takeLast(5)
                val readStr = if (observedRssi != null) "$observedRssi dBm" else "NS"
                val smoothingTag = if (useNeighbourSmoothing && rawMu == 0.0 && mu > 0.0) " [Smoothed]" else ""
                apDetails.append("  • $ssid ($readStr) -> raw μ = ${"%.2f".format(rawMu)}, smoothed μ = ${"%.2f".format(mu)}$smoothingTag\n")
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

    /**
     * Checks if the 3D Fuzzy Matrix model has been built with at least one spot.
     */
    fun isModelLoaded(): Boolean = matrix.isNotEmpty() && allKnownAps.isNotEmpty()

    /**
     * Access the full 3D Matrix grid: SpotLabel -> (BSSID -> DiscreteFuzzyCell).
     */
    fun getMatrix(): Map<String, Map<String, DiscreteFuzzyCell>> = matrix

    /**
     * Set of all known Access Point BSSIDs registered in the matrix.
     */
    fun getAllKnownAps(): Set<String> = allKnownAps

    /**
     * Mapping from BSSID to SSID name.
     */
    fun getApSsidMap(): Map<String, String> = apSsidMap

    /**
     * Generates a structured human-readable text dump of the entire 3D Fuzzy Matrix
     * showing spots, APs, Not Seen frequencies, and empirical discrete distribution bins.
     */
    fun getFormattedMatrix(): String {
        if (matrix.isEmpty()) {
            return "⚠️ Fuzzy Matrix is empty! Record or load survey spots first."
        }

        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("📊 3D FUZZY MATRIX DUMP\n")
        sb.append("Dimensions: ${matrix.size} Spots × ${allKnownAps.size} APs\n")
        sb.append("Smoothing: ${if (useNeighbourSmoothing) "ON (±1dB: 50%, ±2dB: 25%)" else "OFF (Exact Lookup)"}\n")
        sb.append("========================================\n\n")

        for ((spot, cellMap) in matrix) {
            val totalScans = spotTotalScans[spot] ?: 1
            sb.append("📍 SPOT: $spot ($totalScans total survey scans)\n")
            for (bssid in allKnownAps) {
                val cell = cellMap[bssid]
                val ssid = apSsidMap[bssid] ?: bssid.takeLast(6)
                if (cell == null || (cell.rssiFrequencies.isEmpty() && cell.notSeenFrequency >= 1.0)) {
                    sb.append("  • $ssid ($bssid)\n")
                    sb.append("    [Status]: Unheard (NS = 100%, μ = 0.0 for any dBm)\n")
                } else {
                    val nsPercent = (cell.notSeenFrequency * 100).toInt()
                    sb.append("  • $ssid ($bssid)\n")
                    sb.append("    - Not Seen Rate: $nsPercent%\n")
                    val sorted = cell.rssiFrequencies.entries.sortedByDescending { it.value }
                    val binsText = sorted.take(6).joinToString(", ") { "${it.key}dBm: ${(it.value * 100).toInt()}%" }
                    sb.append("    - Empirical Freq: $binsText\n")
                    if (useNeighbourSmoothing) {
                        val smoothedText = sorted.take(4).joinToString(", ") { "${it.key}dBm: ${"%.2f".format(cell.getSmoothedMembership(it.key))}" }
                        sb.append("    - Smoothed Peak μ: $smoothedText\n")
                    }
                }
            }
            sb.append("----------------------------------------\n")
        }
        return sb.toString()
    }
}
