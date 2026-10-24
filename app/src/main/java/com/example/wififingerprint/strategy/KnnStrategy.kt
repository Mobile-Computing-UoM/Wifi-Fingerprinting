package com.example.wififingerprint.strategy

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Strategy 1: Nearest Centroid Classifier (NCC / k-NN Mean) using Euclidean distance
 * across common matching AP dimensions.
 */
class KnnStrategy(var k: Int = 1) : LocalizationStrategy {
    override val name: String = "Strategy 1: Nearest Centroid (k-NN Mean)"
    override val shortCode: String = "Centroid-kNN"

    private var spots: List<RawSurveySpot> = emptyList()

    override fun buildModel(surveys: List<RawSurveySpot>) {
        this.spots = surveys
    }

    override fun infer(query: LiveQueryScan): LocalizationResult {
        if (spots.isEmpty()) {
            return LocalizationResult(
                strategyName = name,
                predictedSpot = null,
                confidenceMargin = null,
                rankings = emptyList(),
                logOutput = "⚠️ No survey spots in database to compare against!\n"
            )
        }

        val log = StringBuilder()
        log.append("\n========================================\n")
        log.append("🔍 [$name (k=$k)]\n")
        log.append("========================================\n")

        // List of Triple(SpotLabel, RMSE_Distance, MatchedDimensionsCount)
        val distanceList = mutableListOf<Triple<String, Double, Int>>()

        for (spot in spots) {
            val surveyMap = spot.bssidToAvgMap
            val commonBssids = query.readings.keys.intersect(surveyMap.keys)

            if (commonBssids.isEmpty()) {
                distanceList.add(Triple(spot.spotLabel, Double.MAX_VALUE, 0))
            } else {
                var sumSquare = 0.0
                for (bssid in commonBssids) {
                    val rSurvey = surveyMap[bssid]!!
                    val rLive = query.readings[bssid]!!
                    sumSquare += (rSurvey - rLive).toDouble().pow(2)
                }
                val rmse = sqrt(sumSquare / commonBssids.size)
                distanceList.add(Triple(spot.spotLabel, rmse, commonBssids.size))
            }
        }

        // Sort ascending by RMSE distance. Ties broken by higher matched AP count.
        distanceList.sortWith(compareBy({ it.second }, { -it.third }))

        val rankings = mutableListOf<SpotRanking>()
        for ((index, item) in distanceList.withIndex()) {
            val rank = index + 1
            val marker = if (rank <= k) "👉 [TOP-$k]" else "   "
            if (item.second == Double.MAX_VALUE) {
                log.append("$marker #$rank ${item.first} -> No common APs matched!\n")
                rankings.add(SpotRanking(rank, item.first, Double.MAX_VALUE, "RMSE (dBm)", "No common APs"))
            } else {
                val distStr = "%.2f".format(item.second)
                log.append("$marker #$rank ${item.first} -> Avg Diff: $distStr dBm (matched ${item.third} APs)\n")
                rankings.add(SpotRanking(rank, item.first, item.second, "RMSE (dBm)", "${item.third} APs matched"))
            }
        }

        val validMatches = distanceList.filter { it.second != Double.MAX_VALUE }
        if (validMatches.isEmpty()) {
            log.append("\n⚠️ Could not determine location: no common APs matched with any saved spot.\n")
            return LocalizationResult(
                strategyName = name,
                predictedSpot = null,
                confidenceMargin = null,
                rankings = rankings,
                logOutput = log.toString()
            )
        }

        val topK = validMatches.take(k)
        if (k > 1) {
            log.append("\nTop-$k Neighbors for Voting:\n")
            for ((idx, cand) in topK.withIndex()) {
                log.append("   ${idx + 1}. ${cand.first} (RMSE: ${"%.2f".format(cand.second)} dBm, ${cand.third} APs)\n")
            }
        }

        // Majority voting among top k candidates
        val votes = topK.groupingBy { it.first }.eachCount()
        val winner = votes.maxByOrNull { it.value }?.key ?: topK.first().first
        val winnerCandidate = topK.first { it.first == winner }

        val margin = if (validMatches.size > 1) {
            val second = validMatches[1].second
            second - winnerCandidate.second
        } else {
            null
        }

        val marginStr = if (margin != null) " (Gap to #2: ${"%.2f".format(margin)} dBm)" else ""
        log.append("\n📍 PREDICTED LOCATION ($shortCode): $winner (RMSE: ${"%.2f".format(winnerCandidate.second)} dBm, ${winnerCandidate.third} APs matched)$marginStr\n")

        return LocalizationResult(
            strategyName = name,
            predictedSpot = winner,
            confidenceMargin = margin,
            rankings = rankings,
            logOutput = log.toString()
        )
    }
}
