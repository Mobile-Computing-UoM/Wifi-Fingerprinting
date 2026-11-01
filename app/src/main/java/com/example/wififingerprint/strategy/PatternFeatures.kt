package com.example.wififingerprint.strategy

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure algorithmic functions for extracting hardware-invariant signal patterns.
 *
 * Eliminates cross-device antenna sensitivity offsets (e.g. +4 dB to +10 dB differences)
 * using mean-centering, pairwise differential RSSI, and rank-order correlation.
 */
object PatternFeatures {

    /**
     * Intersects query scan with reference spot measurements.
     * Returns pairs of (queryRssi, spotAvgRssi) for all shared APs.
     */
    fun extractSharedAps(
        queryReadings: Map<String, Int>,
        spotBssidToAvg: Map<String, Int>
    ): List<Pair<Int, Int>> {
        val sharedPairs = mutableListOf<Pair<Int, Int>>()
        for ((bssid, queryRssi) in queryReadings) {
            val spotRssi = spotBssidToAvg[bssid]
            if (spotRssi != null) {
                sharedPairs.add(Pair(queryRssi, spotRssi))
            }
        }
        return sharedPairs
    }

    /**
     * Computes mean-centered Euclidean distance over shared APs.
     * Subtracts the mean RSSI of each vector, making it invariant to constant dBm shifts.
     */
    fun meanCenteredDistance(pairs: List<Pair<Int, Int>>): Double {
        if (pairs.isEmpty()) return Double.MAX_VALUE
        val meanQ = pairs.map { it.first }.average()
        val meanS = pairs.map { it.second }.average()

        var sumSq = 0.0
        for ((q, s) in pairs) {
            val qNorm = q - meanQ
            val sNorm = s - meanS
            val diff = qNorm - sNorm
            sumSq += diff * diff
        }
        return sqrt(sumSq / pairs.size)
    }

    /**
     * Computes Mean Absolute Pairwise Difference error in dB.
     * For all pairs (i, j), computes |(q_i - q_j) - (s_i - s_j)|.
     * Completely invariant to any constant device offset: (q_i + c) - (q_j + c) = q_i - q_j.
     */
    fun meanPairwiseDifference(pairs: List<Pair<Int, Int>>): Double {
        val n = pairs.size
        if (n < 2) return 0.0

        var totalDiff = 0.0
        var pairCount = 0

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val deltaQ = pairs[i].first - pairs[j].first
                val deltaS = pairs[i].second - pairs[j].second
                totalDiff += abs(deltaQ - deltaS)
                pairCount++
            }
        }

        return if (pairCount > 0) totalDiff / pairCount else 0.0
    }

    /**
     * Computes Rank Footrule distance over shared APs.
     * Ranks APs 1..N based on signal strength (strongest = 1),
     * then averages |rankQ_i - rankS_i|.
     */
    fun rankDistance(pairs: List<Pair<Int, Int>>): Double {
        val n = pairs.size
        if (n <= 1) return 0.0

        // Determine ranks for Query: highest RSSI gets rank 1
        val qOrder = pairs.indices.sortedByDescending { pairs[it].first }
        val qRanks = IntArray(n)
        for ((rank, idx) in qOrder.withIndex()) {
            qRanks[idx] = rank + 1
        }

        // Determine ranks for Reference Spot
        val sOrder = pairs.indices.sortedByDescending { pairs[it].second }
        val sRanks = IntArray(n)
        for ((rank, idx) in sOrder.withIndex()) {
            sRanks[idx] = rank + 1
        }

        var sumRankDiff = 0
        for (i in 0 until n) {
            sumRankDiff += abs(qRanks[i] - sRanks[i])
        }

        return sumRankDiff.toDouble() / n
    }

    /**
     * Unified composite pattern distance:
     * Combines pairwise differences, rank alignment, and unshared AP penalty.
     */
    fun compositeDistance(
        queryReadings: Map<String, Int>,
        spot: RawSurveySpot,
        minSharedAps: Int = 2
    ): Pair<Double, String> {
        val spotAvgMap = spot.bssidToAvgMap
        val sharedPairs = extractSharedAps(queryReadings, spotAvgMap)

        if (sharedPairs.isEmpty()) {
            return Pair(Double.MAX_VALUE, "No shared APs")
        }

        // Relative missing-AP penalty: penalize only if an AP within 20 dB of the spot's strongest AP is absent
        val maxRefRssi = spotAvgMap.values.maxOrNull() ?: -100
        var missingPenalty = 0.0
        for ((bssid, sRssi) in spotAvgMap) {
            if (!queryReadings.containsKey(bssid) && (maxRefRssi - sRssi <= 20)) {
                missingPenalty += 2.0 // 2 dB penalty for dominant expected AP missing
            }
        }

        // Graceful fallback for single shared AP (cannot compute pairwise/rank without at least 2 APs)
        if (sharedPairs.size == 1) {
            val singleDiff = abs(sharedPairs[0].first - sharedPairs[0].second).toDouble()
            val composite = singleDiff + missingPenalty + 5.0
            val details = "shared=1 (single-AP fallback), diff=%.1fdB, penalty=%.1fdB".format(
                singleDiff, missingPenalty
            )
            return Pair(composite, details)
        }

        if (sharedPairs.size < minSharedAps) {
            return Pair(Double.MAX_VALUE, "Insufficient shared APs (${sharedPairs.size}/$minSharedAps)")
        }

        val pairwiseErr = meanPairwiseDifference(sharedPairs)
        val rankErr = rankDistance(sharedPairs)
        val meanCenteredErr = meanCenteredDistance(sharedPairs)

        // Weighted composite score (lower is closer match)
        val composite = (0.50 * pairwiseErr) + (0.30 * meanCenteredErr) + (1.50 * rankErr) + missingPenalty
        val details = "shared=${sharedPairs.size}, pairwise=%.1fdB, rank=%.2f, mc=%.1fdB".format(
            pairwiseErr, rankErr, meanCenteredErr
        )
        return Pair(composite, details)
    }
}
