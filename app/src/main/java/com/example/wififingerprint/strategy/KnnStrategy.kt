package com.example.wififingerprint.strategy

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Strategy 1: True Scan-Level k-Nearest Neighbours (k-NN) Classifier.
 *
 * Stores individual survey scan vectors per spot (supports subsampling N ∈ {10, 30, 50}),
 * computes Euclidean distance across all registered APs with a -100 dBm missing-AP floor,
 * votes among the top-k individual scans (k ∈ {1, 3, 5}), and averages the 2D floor-plan
 * coordinates of the k nearest neighbours (as in RADAR) for metric error evaluation in metres.
 */
class KnnStrategy(
    var k: Int = 1,
    var maxSamplesPerSpot: Int = Int.MAX_VALUE,
    val missingRssiFloor: Int = -100
) : LocalizationStrategy {

    override val name: String = "Strategy 1: Scan-Level k-NN"
    override val shortCode: String = "Scan-kNN"

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

        fun getCoordinatesForSpot(spotLabel: String): Pair<Double, Double>? {
            return FLOOR_PLAN_COORDS[spotLabel.trim().lowercase()]
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

        for (spot in surveys) {
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
     * Estimates 2D floor-plan coordinates (x, y) in metres by averaging the coordinates
     * of the top-k nearest individual scan vectors (RADAR k-NN coordinate interpolation).
     */
    fun estimateCoordinatesMetres(query: LiveQueryScan): Pair<Double, Double>? {
        val matches = computeAllScanDistances(query)
        if (matches.isEmpty()) return null
        val topK = matches.take(k.coerceAtLeast(1))
        val coords = topK.mapNotNull { getCoordinatesForSpot(it.spotLabel) }
        if (coords.isEmpty()) return null
        val avgX = coords.map { it.first }.average()
        val avgY = coords.map { it.second }.average()
        return Pair(avgX, avgY)
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
                val rSurvey = scan.readings[bssid] ?: missingRssiFloor
                val rLive = query.readings[bssid] ?: missingRssiFloor
                val diff = (rSurvey - rLive).toDouble()
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
        log.append("🔍 [$name (k=$effectiveK, ${individualScans.size} stored scans, ${allKnownBssids.size} APs, floor=$missingRssiFloor dBm)]\n")
        log.append("========================================\n")

        val allMatches = computeAllScanDistances(query)
        val topK = allMatches.take(effectiveK)

        // Vote tally among the top-k individual scan neighbours
        val voteGroups = topK.groupBy { it.spotLabel }
        val winner = voteGroups.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<ScanDistanceMatch>>> { it.value.size }
                .thenBy { entry -> entry.value.map { it.euclideanDist }.average() }
        ).first().key

        // Spot-level summary rankings (ordered by minimum Euclidean distance per spot)
        val bestPerSpot = allMatches.groupBy { it.spotLabel }.mapValues { (_, list) ->
            list.first()
        }.values.sortedBy { it.euclideanDist }

        val rankings = mutableListOf<SpotRanking>()
        for ((index, spotBest) in bestPerSpot.withIndex()) {
            val rank = index + 1
            val votesForSpot = voteGroups[spotBest.spotLabel]?.size ?: 0
            val marker = if (spotBest.spotLabel == winner) "👉 [WINNER]" else "   "
            val distStr = "%.2f".format(spotBest.euclideanDist)
            val rmseStr = "%.2f".format(spotBest.rmseDist)
            log.append("$marker #$rank ${spotBest.spotLabel} -> Min Dist: $distStr dBm (RMSE: $rmseStr dBm, Top-$effectiveK Votes: $votesForSpot/$effectiveK)\n")
            rankings.add(
                SpotRanking(
                    rank = rank,
                    spotLabel = spotBest.spotLabel,
                    scoreMetric = spotBest.euclideanDist,
                    metricLabel = "Euclidean (dBm)",
                    details = "Votes: $votesForSpot/$effectiveK, RMSE: $rmseStr dBm"
                )
            )
        }

        log.append("\nTop-$effectiveK Nearest Individual Scans:\n")
        for ((idx, m) in topK.withIndex()) {
            log.append("   ${idx + 1}. ${m.spotLabel} [scan #${m.scanIndex + 1}] -> Dist: ${"%.2f".format(m.euclideanDist)} dBm\n")
        }

        val estCoords = estimateCoordinatesMetres(query)
        lastEstimatedCoords = estCoords
        if (estCoords != null) {
            log.append("📐 Estimated 2D Position (Top-$effectiveK avg): (${"%.2f".format(estCoords.first)} m, ${"%.2f".format(estCoords.second)} m)\n")
        }

        val winnerBestDist = bestPerSpot.first { it.spotLabel == winner }.euclideanDist
        val runnerUpDist = bestPerSpot.firstOrNull { it.spotLabel != winner }?.euclideanDist
        val margin = if (runnerUpDist != null) runnerUpDist - winnerBestDist else null
        val marginStr = if (margin != null) " (Gap to #2: ${"%.2f".format(margin)} dBm)" else ""

        log.append("\n📍 PREDICTED LOCATION ($shortCode): $winner (Votes: ${voteGroups[winner]?.size ?: 1}/$effectiveK, Min Dist: ${"%.2f".format(winnerBestDist)} dBm)$marginStr\n")

        return LocalizationResult(
            strategyName = name,
            predictedSpot = winner,
            confidenceMargin = margin,
            rankings = rankings,
            logOutput = log.toString()
        )
    }
}
