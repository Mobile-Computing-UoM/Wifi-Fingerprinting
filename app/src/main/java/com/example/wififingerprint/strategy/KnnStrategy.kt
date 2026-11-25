package com.example.wififingerprint.strategy

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Strategy 1: Distance-Weighted k-Nearest Neighbours (WKNN) Classifier.
 *
 * Stores individual survey scan vectors per spot (supports subsampling N ∈ {10, 30, 50}),
 * computes robust signal-space distance across registered APs with an adaptive missing-AP penalty,
 * calculates inverse-square distance weights (w_i = 1 / max(d_i, ε)^2) among the top-k nearest scans (k ∈ {1, 3, 5}),
 * performs accumulated distance-weighted voting for the winning spot, and computes distance-weighted
 * 2D floor-plan coordinate interpolation for high-precision metric positioning.
 */
class KnnStrategy(
    var k: Int = 1,
    var maxSamplesPerSpot: Int = Int.MAX_VALUE,
    val missingRssiFloor: Int = -100,
    val detectionThreshold: Int = -90,
    val weightExponent: Double = 2.0
) : LocalizationStrategy {

    override val name: String = "Strategy 1: Weighted k-NN (WKNN)"
    override val shortCode: String = "WKNN"

    data class IndividualScanRecord(
        val spotLabel: String,
        val scanIndex: Int,
        val readings: Map<String, Int>
    )

    data class ScanDistanceMatch(
        val spotLabel: String,
        val scanIndex: Int,
        val euclideanDist: Double,
        val rmseDist: Double
    )

    private var spots: List<RawSurveySpot> = emptyList()
    private val individualScans = mutableListOf<IndividualScanRecord>()
    private val allKnownBssids = linkedSetOf<String>()
    var lastEstimatedCoords: Pair<Double, Double>? = null
        private set

    companion object {
        // Measured 2D floor-plan coordinates (x, y) in metres for the 8 reference spots
        val FLOOR_PLAN_COORDS: Map<String, Pair<Double, Double>> = mapOf(
            "prayerroom" to Pair(0.0, 0.0),
            "prayer room" to Pair(0.0, 0.0),
            "hall" to Pair(2.5, 1.0),
            "hall2" to Pair(4.5, 1.0),
            "hall 2" to Pair(4.5, 1.0),
            "hallway 2" to Pair(4.5, 1.0),
            "bedroom" to Pair(2.5, 3.5),
            "coridoor" to Pair(4.5, 3.0),
            "corridor" to Pair(4.5, 3.0),
            "room2" to Pair(7.0, 1.0),
            "room 2" to Pair(7.0, 1.0),
            "kitchen" to Pair(7.0, 3.0),
            "storeroom" to Pair(9.0, 2.0)
        )

        val spotCoords: MutableMap<String, Pair<Double, Double>> = mutableMapOf()

        fun registerSpotCoords(spotLabel: String, x: Double, y: Double) {
            spotCoords[spotLabel.trim().lowercase()] = Pair(x, y)
        }

        fun getCoordinatesForSpot(spotLabel: String): Pair<Double, Double>? {
            val clean = spotLabel.trim().lowercase()
            val dynamic = spotCoords[clean]
            if (dynamic != null && (dynamic.first != 0.0 || dynamic.second != 0.0)) {
                return dynamic
            }
            return FLOOR_PLAN_COORDS[clean] ?: dynamic
        }

        fun computeErrorMeters(
            actualSpot: String,
            predictedSpot: String?,
            estimatedCoords: Pair<Double, Double>? = null
        ): Double? {
            val actualCoords = getCoordinatesForSpot(actualSpot) ?: return null
            val predCoords = estimatedCoords ?: (predictedSpot?.let { getCoordinatesForSpot(it) }) ?: return null
            val dx = actualCoords.first - predCoords.first
            val dy = actualCoords.second - predCoords.second
            return sqrt(dx * dx + dy * dy)
        }
    }

    override fun buildModel(surveys: List<RawSurveySpot>) {
        this.spots = surveys
        individualScans.clear()
        allKnownBssids.clear()
        spotCoords.clear()

        for (spot in surveys) {
            spotCoords[spot.spotLabel.trim().lowercase()] = Pair(spot.coordX, spot.coordY)
            for (m in spot.measurements) {
                allKnownBssids.add(m.bssid)
            }
        }

        for (spot in surveys) {
            val maxRawLen = spot.measurements.maxOfOrNull { it.rawReadings.size } ?: 0
            val totalAvailable = maxOf(spot.totalScans, maxRawLen, 1)
            val nToUse = minOf(totalAvailable, maxSamplesPerSpot)

            for (idx in 0 until nToUse) {
                val scanMap = mutableMapOf<String, Int>()
                for (m in spot.measurements) {
                    if (idx < m.rawReadings.size) {
                        scanMap[m.bssid] = m.rawReadings[idx]
                    } else if (m.rawReadings.isEmpty() && idx == 0) {
                        scanMap[m.bssid] = m.avgRssi
                    }
                }
                individualScans.add(IndividualScanRecord(spot.spotLabel, idx, scanMap))
            }
        }
    }

    /**
     * Estimates 2D floor-plan coordinates (x, y) in metres by calculating the distance-weighted
     * centroid of the top-k nearest individual scan vectors (WKNN coordinate interpolation).
     */
    fun estimateCoordinatesMetres(query: LiveQueryScan): Pair<Double, Double>? {
        val matches = computeAllScanDistances(query)
        if (matches.isEmpty()) return null
        val topK = matches.take(k.coerceAtLeast(1))
        
        val validPairs = topK.mapNotNull { m ->
            val c = getCoordinatesForSpot(m.spotLabel)
            if (c != null) Pair(m, c) else null
        }
        if (validPairs.isEmpty()) return null

        val weights = validPairs.map { (m, _) ->
            1.0 / maxOf(m.euclideanDist, 0.001).pow(weightExponent)
        }
        val sumW = weights.sum()
        if (sumW <= 0.0) return null

        var weightedX = 0.0
        var weightedY = 0.0
        for (i in validPairs.indices) {
            val normW = weights[i] / sumW
            val coords = validPairs[i].second
            weightedX += normW * coords.first
            weightedY += normW * coords.second
        }

        return Pair(weightedX, weightedY)
    }

    /**
     * Computes the adaptive penalty difference between a recorded RSSI and a missing AP.
     * When an AP is observed near sensitivity limits (< detectionThreshold, e.g. -90 dBm),
     * missing it is normal receiver dropping and should receive a small difference penalty
     * (|r - threshold|). However, when a strong AP is missing (e.g. -50 dBm), it receives
     * the full missing floor penalty (|r - missingRssiFloor|).
     */
    private fun computeAdaptiveDiff(r1: Int?, r2: Int?): Double {
        if (r1 != null && r2 != null) {
            return (r1 - r2).toDouble()
        }
        val observed = r1 ?: r2 ?: return 0.0
        return if (observed <= detectionThreshold) {
            // Weak AP: missing it is likely threshold attenuation
            (observed - detectionThreshold).toDouble()
        } else {
            // Strong or moderate AP: missing it is a significant negative indicator
            (observed - missingRssiFloor).toDouble()
        }
    }

    private fun computeAllScanDistances(query: LiveQueryScan): List<ScanDistanceMatch> {
        if (individualScans.isEmpty()) return emptyList()
        val apUniverse = if (allKnownBssids.isNotEmpty()) allKnownBssids else query.readings.keys
        if (apUniverse.isEmpty()) return emptyList()

        val results = ArrayList<ScanDistanceMatch>(individualScans.size)
        val dimCount = apUniverse.size

        for (scan in individualScans) {
            var sumSq = 0.0
            for (bssid in apUniverse) {
                val rSurvey = scan.readings[bssid]
                val rLive = query.readings[bssid]
                val diff = computeAdaptiveDiff(rSurvey, rLive)
                sumSq += diff * diff
            }
            val euclid = sqrt(sumSq)
            val rmse = sqrt(sumSq / dimCount)
            results.add(ScanDistanceMatch(scan.spotLabel, scan.scanIndex, euclid, rmse))
        }

        results.sortBy { it.euclideanDist }
        return results
    }

    override fun infer(query: LiveQueryScan): LocalizationResult {
        if (spots.isEmpty() || individualScans.isEmpty()) {
            return LocalizationResult(
                strategyName = name,
                predictedSpot = null,
                confidenceMargin = null,
                rankings = emptyList(),
                logOutput = "⚠️ No survey spots in database to compare against!\n"
            )
        }

        val effectiveK = k.coerceAtLeast(1)
        val log = StringBuilder()
        log.append("\n========================================\n")
        log.append("🔍 [$name (k=$effectiveK, p=$weightExponent, ${individualScans.size} stored scans, ${allKnownBssids.size} APs, floor=$missingRssiFloor dBm, thres=$detectionThreshold dBm)]\n")
        log.append("========================================\n")

        val allMatches = computeAllScanDistances(query)
        val topK = allMatches.take(effectiveK)

        // Calculate inverse-power distance weights for top-k neighbors: w_i = 1 / max(d_i, 0.001)^p
        val weights = topK.map { 1.0 / maxOf(it.euclideanDist, 0.001).pow(weightExponent) }
        val sumW = weights.sum().coerceAtLeast(1e-9)
        val normWeights = weights.map { it / sumW }

        // Distance-weighted vote accumulation per candidate spot
        val spotAccumWeights = mutableMapOf<String, Double>()
        val spotTopKVotes = mutableMapOf<String, Int>()

        for (i in topK.indices) {
            val label = topK[i].spotLabel
            spotAccumWeights[label] = (spotAccumWeights[label] ?: 0.0) + normWeights[i]
            spotTopKVotes[label] = (spotTopKVotes[label] ?: 0) + 1
        }

        // Winner is candidate spot with highest accumulated distance-weighted score
        val winner = spotAccumWeights.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenBy { entry ->
                    // Tie-break with minimum Euclidean distance if identical accumulated weight
                    allMatches.firstOrNull { it.spotLabel == entry.key }?.euclideanDist ?: Double.MAX_VALUE
                }
        ).first().key

        // Spot-level summary rankings (ordered by minimum Euclidean distance per spot)
        val bestPerSpot = allMatches.groupBy { it.spotLabel }.mapValues { (_, list) ->
            list.first()
        }.values.sortedBy { it.euclideanDist }

        val rankings = mutableListOf<SpotRanking>()
        for ((index, spotBest) in bestPerSpot.withIndex()) {
            val rank = index + 1
            val votesForSpot = spotTopKVotes[spotBest.spotLabel] ?: 0
            val weightShare = (spotAccumWeights[spotBest.spotLabel] ?: 0.0) * 100.0
            val marker = if (spotBest.spotLabel == winner) "👉 [WINNER]" else "   "
            val distStr = "%.2f".format(spotBest.euclideanDist)
            val rmseStr = "%.2f".format(spotBest.rmseDist)
            val weightStr = "%.1f".format(weightShare)
            log.append("$marker #$rank ${spotBest.spotLabel} -> Min Dist: $distStr dBm (RMSE: $rmseStr dBm, Top-$effectiveK Votes: $votesForSpot/$effectiveK, Weight: $weightStr%)\n")
            rankings.add(
                SpotRanking(
                    rank = rank,
                    spotLabel = spotBest.spotLabel,
                    scoreMetric = spotBest.euclideanDist,
                    metricLabel = "Euclidean (dBm)",
                    details = "Votes: $votesForSpot/$effectiveK ($weightStr%), RMSE: $rmseStr dBm"
                )
            )
        }

        log.append("\nTop-$effectiveK Nearest Individual Scans:\n")
        for ((idx, m) in topK.withIndex()) {
            val wPct = "%.1f".format(normWeights[idx] * 100.0)
            log.append("   ${idx + 1}. ${m.spotLabel} [scan #${m.scanIndex + 1}] -> Dist: ${"%.2f".format(m.euclideanDist)} dBm (Weight: $wPct%)\n")
        }

        val estCoords = estimateCoordinatesMetres(query)
        lastEstimatedCoords = estCoords
        if (estCoords != null) {
            log.append("📐 Estimated 2D Position (Distance-Weighted): (${"%.2f".format(estCoords.first)} m, ${"%.2f".format(estCoords.second)} m)\n")
        }

        val winnerBestDist = bestPerSpot.first { it.spotLabel == winner }.euclideanDist
        val runnerUpDist = bestPerSpot.firstOrNull { it.spotLabel != winner }?.euclideanDist
        val margin = if (runnerUpDist != null) runnerUpDist - winnerBestDist else null
        val marginStr = if (margin != null) " (Gap to #2: ${"%.2f".format(margin)} dBm)" else ""
        val winnerWeightPct = "%.1f".format((spotAccumWeights[winner] ?: 1.0) * 100.0)

        log.append("\n📍 PREDICTED LOCATION ($shortCode): $winner (Weight: $winnerWeightPct%, Votes: ${spotTopKVotes[winner] ?: 1}/$effectiveK, Min Dist: ${"%.2f".format(winnerBestDist)} dBm)$marginStr\n")

        return LocalizationResult(
            strategyName = name,
            predictedSpot = winner,
            confidenceMargin = margin,
            rankings = rankings,
            logOutput = log.toString()
        )
    }
}
