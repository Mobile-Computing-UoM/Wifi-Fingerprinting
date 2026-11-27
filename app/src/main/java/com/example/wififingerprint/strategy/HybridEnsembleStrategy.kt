package com.example.wififingerprint.strategy

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Strategy 5: Smart Hybrid Ensemble (WKNN + Mamdani FIS).
 *
 * Fuses Distance-Weighted k-NN (WKNN) signal-space proximity with textbook Mamdani Fuzzy
 * Inference System (FIS) rule firing strengths via normalized probability blending.
 * Optionally blends Cross-Device Pattern matching when operating on an uncalibrated
 * imported reference map from a different device.
 */
class HybridEnsembleStrategy(
    private val knnStrategy: KnnStrategy,
    private val mamdaniStrategy: MamdaniFuzzyStrategy,
    private val patternStrategy: PatternStrategy? = null,
    var useCrossDeviceBoost: Boolean = false
) : LocalizationStrategy {

    override val name: String = "Smart Hybrid (WKNN + Mamdani FIS)"
    override val shortCode: String = "Hybrid"

    var lastEstimatedCoords: Pair<Double, Double>? = null
        private set

    var lastConfidencePercent: Int = 0
        private set

    override fun buildModel(surveys: List<RawSurveySpot>) {
        knnStrategy.buildModel(surveys)
        mamdaniStrategy.buildModel(surveys)
        patternStrategy?.buildModel(surveys)
        lastEstimatedCoords = null
        lastConfidencePercent = 0
    }

    override fun infer(query: LiveQueryScan): LocalizationResult {
        val knnResult = knnStrategy.infer(query)
        val mamdaniResult = mamdaniStrategy.infer(query)

        val allSpots = (knnResult.rankings.map { it.spotLabel } + mamdaniResult.rankings.map { it.spotLabel }).distinct()
        if (allSpots.isEmpty() || query.readings.isEmpty()) {
            lastEstimatedCoords = null
            lastConfidencePercent = 0
            return LocalizationResult(
                strategyName = name,
                predictedSpot = null,
                confidenceMargin = null,
                rankings = emptyList(),
                logOutput = "⚠️ Smart Hybrid model or query is empty."
            )
        }

        val knnProbs = normalizeInverseDistance(knnResult.rankings, allSpots)
        val mamdaniProbs = normalizeHigherIsBetter(mamdaniResult.rankings, allSpots)

        val patternActive = useCrossDeviceBoost && patternStrategy != null
        val patternProbs = if (patternActive) {
            val patResult = patternStrategy!!.infer(query)
            normalizeInverseDistance(patResult.rankings, allSpots)
        } else {
            emptyMap()
        }

        val wKnn = if (patternActive) 0.35 else 0.55
        val wMamdani = if (patternActive) 0.35 else 0.45
        val wPattern = if (patternActive) 0.30 else 0.0

        val fusedScores = allSpots.associateWith { spot ->
            val pK = knnProbs[spot] ?: 0.0
            val pM = mamdaniProbs[spot] ?: 0.0
            val pP = patternProbs[spot] ?: 0.0
            (wKnn * pK) + (wMamdani * pM) + (wPattern * pP)
        }

        val sorted = fusedScores.entries.sortedByDescending { it.value }
        val winner = sorted.first().key
        val bestProb = sorted.first().value
        val secondProb = sorted.getOrNull(1)?.value ?: 0.0
        val margin = if (sorted.size > 1) bestProb - secondProb else bestProb

        lastConfidencePercent = (bestProb * 100.0).roundToInt().coerceIn(0, 100)
        lastEstimatedCoords = blendCoordinates(
            knnStrategy.lastEstimatedCoords,
            mamdaniStrategy.lastEstimatedCoords,
            wKnn,
            wMamdani
        )

        val log = StringBuilder()
        log.append("\n--- $name ---\n")
        log.append("Weights: WKNN=${(wKnn * 100).roundToInt()}%, Mamdani=${(wMamdani * 100).roundToInt()}%")
        if (patternActive) {
            log.append(", Pattern=${(wPattern * 100).roundToInt()}% (Cross-Device Boost)")
        }
        log.append("\n")

        val rankings = sorted.mapIndexed { idx, (spot, prob) ->
            val rank = idx + 1
            val pctStr = String.format(Locale.US, "%.1f", prob * 100.0)
            val kPct = ((knnProbs[spot] ?: 0.0) * 100.0).roundToInt()
            val mPct = ((mamdaniProbs[spot] ?: 0.0) * 100.0).roundToInt()
            val details = if (patternActive) {
                val pPct = ((patternProbs[spot] ?: 0.0) * 100.0).roundToInt()
                "WKNN: $kPct%, Mamdani: $mPct%, Pattern: $pPct%"
            } else {
                "WKNN: $kPct%, Mamdani: $mPct%"
            }
            val marker = if (rank == 1) "👉 [WINNER]" else "   "
            log.append("$marker #$rank $spot -> $pctStr% ($details)\n")
            SpotRanking(
                rank = rank,
                spotLabel = spot,
                scoreMetric = prob * 100.0,
                metricLabel = "Confidence %",
                details = details
            )
        }

        lastEstimatedCoords?.let { (x, y) ->
            log.append("📐 Blended Position: (${String.format(Locale.US, "%.2f", x)} m, ${String.format(Locale.US, "%.2f", y)} m)\n")
        }
        log.append("📍 PREDICTED LOCATION ($shortCode): $winner ($lastConfidencePercent% Confidence)\n")

        return LocalizationResult(
            strategyName = name,
            predictedSpot = winner,
            confidenceMargin = margin,
            rankings = rankings,
            logOutput = log.toString()
        )
    }

    private fun normalizeInverseDistance(
        rankings: List<SpotRanking>,
        allSpots: List<String>
    ): Map<String, Double> {
        val rawMap = mutableMapOf<String, Double>()
        for (spot in allSpots) {
            val metric = rankings.firstOrNull { it.spotLabel == spot }?.scoreMetric
            val raw = if (metric != null && metric < Double.MAX_VALUE / 2.0 && metric.isFinite()) {
                1.0 / maxOf(metric, 0.5).pow(2.0)
            } else {
                0.0
            }
            rawMap[spot] = raw
        }
        val sum = rawMap.values.sum()
        return if (sum > 1e-12) {
            rawMap.mapValues { (_, v) -> v / sum }
        } else {
            val uniform = 1.0 / allSpots.size.coerceAtLeast(1)
            allSpots.associateWith { uniform }
        }
    }

    private fun normalizeHigherIsBetter(
        rankings: List<SpotRanking>,
        allSpots: List<String>
    ): Map<String, Double> {
        val rawMap = mutableMapOf<String, Double>()
        for (spot in allSpots) {
            val metric = rankings.firstOrNull { it.spotLabel == spot }?.scoreMetric ?: 0.0
            rawMap[spot] = if (metric.isFinite() && metric > 0.0) metric else 0.0
        }
        val sum = rawMap.values.sum()
        return if (sum > 1e-12) {
            rawMap.mapValues { (_, v) -> v / sum }
        } else {
            val uniform = 1.0 / allSpots.size.coerceAtLeast(1)
            allSpots.associateWith { uniform }
        }
    }

    private fun blendCoordinates(
        cKnn: Pair<Double, Double>?,
        cMamdani: Pair<Double, Double>?,
        wKnn: Double,
        wMamdani: Double
    ): Pair<Double, Double>? {
        return when {
            cKnn != null && cMamdani != null -> {
                val totalW = (wKnn + wMamdani).coerceAtLeast(1e-9)
                val x = (wKnn * cKnn.first + wMamdani * cMamdani.first) / totalW
                val y = (wKnn * cKnn.second + wMamdani * cMamdani.second) / totalW
                Pair(x, y)
            }
            cKnn != null -> cKnn
            else -> cMamdani
        }
    }
}
