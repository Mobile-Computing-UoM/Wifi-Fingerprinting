package com.example.wififingerprint.strategy

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Linguistic Fuzzy Terms for Wi-Fi RSSI signal strength (in dBm).
 * Uses 9 fine-grained, overlapping Triangular and Trapezoidal membership functions
 * forming a Ruspini partition (Σ μ(x) = 1.0) across [-100, 0] dBm with 6 dB peak spacing.
 */
enum class FuzzyRssiTerm(val label: String) {
    UNHEARD("UNHEARD"),
    EXTREMELY_WEAK("EXTREMELY_WEAK"),
    VERY_WEAK("VERY_WEAK"),
    WEAK("WEAK"),
    FAIR("FAIR"),
    MODERATE("MODERATE"),
    GOOD("GOOD"),
    STRONG("STRONG"),
    VERY_STRONG("VERY_STRONG");

    /**
     * Evaluates the membership degree μ(rssi) ∈ [0.0, 1.0] for this linguistic term.
     */
    fun membership(rssi: Int?): Double {
        if (rssi == null) {
            return if (this == UNHEARD) 1.0 else 0.0
        }
        val x = rssi.toDouble()
        return when (this) {
            UNHEARD -> trapezoid(x, -200.0, -200.0, -96.0, -90.0)
            EXTREMELY_WEAK -> triangle(x, -96.0, -90.0, -84.0)
            VERY_WEAK -> triangle(x, -90.0, -84.0, -78.0)
            WEAK -> triangle(x, -84.0, -78.0, -72.0)
            FAIR -> triangle(x, -78.0, -72.0, -66.0)
            MODERATE -> triangle(x, -72.0, -66.0, -60.0)
            GOOD -> triangle(x, -66.0, -60.0, -54.0)
            STRONG -> triangle(x, -60.0, -54.0, -48.0)
            VERY_STRONG -> trapezoid(x, -54.0, -48.0, 0.0, 0.0)
        }
    }

    companion object {
        fun triangle(x: Double, a: Double, b: Double, c: Double): Double {
            if (x <= a || x >= c) return 0.0
            if (x == b) return 1.0
            return if (x < b) {
                (x - a) / (b - a)
            } else {
                (c - x) / (c - b)
            }
        }

        fun trapezoid(x: Double, a: Double, b: Double, c: Double, d: Double): Double {
            if (x < a || x > d) return 0.0
            if (x >= b && x <= c) return 1.0
            if (x < b) {
                return if (b == a) 1.0 else (x - a) / (b - a)
            }
            return if (d == c) 1.0 else (d - x) / (d - c)
        }

        /**
         * Fuzzifies a crisp RSSI reading into membership grades across all linguistic terms.
         */
        fun fuzzify(rssi: Int?): Map<FuzzyRssiTerm, Double> {
            return entries.associateWith { it.membership(rssi) }
        }

        /**
         * Returns the dominant linguistic term (highest membership) for a crisp RSSI reading.
         */
        fun dominantTerm(rssi: Int?): FuzzyRssiTerm {
            if (rssi == null) return UNHEARD
            return entries.maxByOrNull { it.membership(rssi) } ?: VERY_WEAK
        }
    }
}

/**
 * Single antecedent clause in a Mamdani IF-THEN rule: (AP BSSID is LinguisticTerm).
 */
data class MamdaniAntecedent(
    val bssid: String,
    val ssid: String,
    val term: FuzzyRssiTerm,
    val presenceRate: Double
)

/**
 * Complete Mamdani IF-THEN Rule automatically extracted from a surveyed reference spot:
 *   IF (AP_1 is Term_1) AND (AP_2 is Term_2) ... THEN Location is Spot (coordX, coordY)
 */
data class MamdaniRule(
    val ruleId: Int,
    val spotLabel: String,
    val coordX: Double,
    val coordY: Double,
    val antecedents: List<MamdaniAntecedent>
) {
    fun formatRuleText(): String {
        val conds = antecedents.joinToString(" AND ") { "${it.ssid} is ${it.term.label}" }
        return "R$ruleId: IF ($conds) THEN Location is $spotLabel (${String.format(Locale.US, "%.1f, %.1f", coordX, coordY)} m)"
    }
}

/**
 * Strategy 4: Textbook Mamdani Fuzzy Inference System (FIS).
 *
 * Implements the complete 5-stage Mamdani pipeline:
 *  1. Fuzzification: Crisp RSSI (dBm) -> Linguistic Fuzzy Sets (UNHEARD, VERY_WEAK, WEAK, MODERATE, STRONG).
 *  2. Rule Base Generation: Automatically extracts human-readable IF-THEN rules from survey spots.
 *  3. Inference (Rule Evaluation): Mamdani Min t-norm (with soft-AND compensatory fallback if all strict mins are 0).
 *  4. Implication & Aggregation: Min-clipping of triangular spatial output fuzzy sets + Max-union aggregation
 *     over continuous X and Y coordinate domains.
 *  5. Defuzzification: Center of Gravity (COG / Centroid) numerical integration to yield continuous (x, y) metres.
 */
class MamdaniFuzzyStrategy(
    private val outputSpreadMeters: Double = 2.0,
    private val gridStepMeters: Double = 0.1
) : LocalizationStrategy {

    override val name: String = "Strategy 4: Mamdani Fuzzy System (FIS)"
    override val shortCode: String = "MamdaniFIS"

    private val rules = mutableListOf<MamdaniRule>()
    private val allKnownAps = mutableSetOf<String>()
    private val apSsidMap = mutableMapOf<String, String>()
    private val spotCoords = mutableMapOf<String, Pair<Double, Double>>()

    /** Last continuous (x, y) metres produced by Centroid (COG) defuzzification. */
    var lastEstimatedCoords: Pair<Double, Double>? = null
        private set

    fun isModelLoaded(): Boolean = rules.isNotEmpty()

    fun getRules(): List<MamdaniRule> = rules.toList()

    fun getCoordinatesForSpot(spotLabel: String): Pair<Double, Double>? {
        val clean = spotLabel.trim().lowercase(Locale.US)
        val dynamic = spotCoords[clean]
        if (dynamic != null && (dynamic.first != 0.0 || dynamic.second != 0.0)) {
            return dynamic
        }
        return KnnStrategy.getCoordinatesForSpot(clean) ?: dynamic
    }

    override fun buildModel(surveys: List<RawSurveySpot>) {
        rules.clear()
        allKnownAps.clear()
        apSsidMap.clear()
        spotCoords.clear()
        lastEstimatedCoords = null

        if (surveys.isEmpty()) return

        // 1. Collect all known APs and spot coordinates
        for (spot in surveys) {
            val resolvedCoords = resolveSpotCoordinates(spot)
            spotCoords[spot.spotLabel.trim().lowercase(Locale.US)] = resolvedCoords
            for (m in spot.measurements) {
                allKnownAps.add(m.bssid)
                if (m.ssid.isNotBlank() && m.ssid != "Unknown") {
                    apSsidMap[m.bssid] = m.ssid
                }
            }
        }

        // 2. Automatically mine one Mamdani IF-THEN rule per surveyed spot
        var ruleCounter = 1
        for (spot in surveys) {
            val totalScans = if (spot.totalScans > 0) spot.totalScans else 1
            val measurementByBssid = spot.measurements.associateBy { it.bssid }
            val antecedents = mutableListOf<MamdaniAntecedent>()

            for (bssid in allKnownAps) {
                val m = measurementByBssid[bssid]
                val ssid = apSsidMap[bssid] ?: m?.ssid ?: bssid.takeLast(5)

                if (m != null && m.rawReadings.isNotEmpty()) {
                    val presenceRate = (m.rawReadings.size.toDouble() / totalScans).coerceIn(0.0, 1.0)
                    if (presenceRate < 0.20) {
                        antecedents.add(MamdaniAntecedent(bssid, ssid, FuzzyRssiTerm.UNHEARD, presenceRate))
                    } else {
                        // Pick the linguistic term with the highest average membership over raw survey readings
                        val bestTerm = FuzzyRssiTerm.entries
                            .filter { it != FuzzyRssiTerm.UNHEARD }
                            .maxByOrNull { term ->
                                m.rawReadings.map { term.membership(it) }.average()
                            } ?: FuzzyRssiTerm.dominantTerm(m.avgRssi)

                        antecedents.add(MamdaniAntecedent(bssid, ssid, bestTerm, presenceRate))
                    }
                } else {
                    antecedents.add(MamdaniAntecedent(bssid, ssid, FuzzyRssiTerm.UNHEARD, 0.0))
                }
            }

            // Sort antecedents so strongly-present APs appear first for readability
            val orderedAntecedents = antecedents.sortedByDescending { it.presenceRate }
            val coords = resolveSpotCoordinates(spot)
            rules.add(
                MamdaniRule(
                    ruleId = ruleCounter++,
                    spotLabel = spot.spotLabel,
                    coordX = coords.first,
                    coordY = coords.second,
                    antecedents = orderedAntecedents
                )
            )
        }
    }

    private fun resolveSpotCoordinates(spot: RawSurveySpot): Pair<Double, Double> {
        if (spot.coordX != 0.0 || spot.coordY != 0.0) {
            return Pair(spot.coordX, spot.coordY)
        }
        return KnnStrategy.getCoordinatesForSpot(spot.spotLabel) ?: Pair(spot.coordX, spot.coordY)
    }

    override fun infer(query: LiveQueryScan): LocalizationResult {
        if (rules.isEmpty() || allKnownAps.isEmpty()) {
            lastEstimatedCoords = null
            return LocalizationResult(
                strategyName = name,
                predictedSpot = null,
                confidenceMargin = null,
                rankings = emptyList(),
                logOutput = "⚠️ Mamdani FIS rule base is empty! Survey spots first.\n"
            )
        }

        val log = StringBuilder()
        log.append("\n========================================\n")
        log.append(" 🧠 [$shortCode] Mamdani Fuzzy Inference\n")
        log.append("========================================\n")

        // --- STAGE 1: FUZZIFICATION ---
        log.append("1. Fuzzification (Live Scan -> Linguistic Terms):\n")
        val fuzzifiedInputs = mutableMapOf<String, Map<FuzzyRssiTerm, Double>>()
        for (bssid in allKnownAps) {
            val rssi = query.readings[bssid]
            val memberships = FuzzyRssiTerm.fuzzify(rssi)
            fuzzifiedInputs[bssid] = memberships
            if (rssi != null) {
                val ssid = apSsidMap[bssid] ?: query.bssidToSsidMap[bssid] ?: bssid.takeLast(5)
                val activeTerms = memberships.entries
                    .filter { it.value > 0.0 }
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key.label}=${String.format(Locale.US, "%.2f", it.value)}" }
                log.append("   • $ssid ($rssi dBm) -> [$activeTerms]\n")
            }
        }

        // --- STAGE 2: RULE EVALUATION (Mamdani Min t-norm + Compensatory Soft-AND Fallback) ---
        data class RuleActivation(
            val rule: MamdaniRule,
            val strictMinStrength: Double,
            val meanStrength: Double,
            val effectiveStrength: Double
        )

        val rawEvaluations = rules.map { rule ->
            // Evaluate antecedents relevant to this rule (heard in query OR present in rule)
            val activeAntecedents = rule.antecedents.filter { ant ->
                query.readings.containsKey(ant.bssid) || ant.term != FuzzyRssiTerm.UNHEARD
            }
            val evalList = if (activeAntecedents.isNotEmpty()) activeAntecedents else rule.antecedents

            val degrees = evalList.map { ant ->
                fuzzifiedInputs[ant.bssid]?.get(ant.term) ?: 0.0
            }
            val strictMin = degrees.minOrNull() ?: 0.0
            val meanDeg = if (degrees.isNotEmpty()) degrees.average() else 0.0
            Triple(rule, strictMin, meanDeg)
        }

        val anyStrictFired = rawEvaluations.any { it.second > 0.0 }
        val activations = rawEvaluations.map { (rule, strictMin, meanDeg) ->
            // Classic Mamdani uses strict Min t-norm; if a single noisy AP zeroes out every rule,
            // fall back gracefully to compensatory average AND so positioning never returns None.
            val alpha = if (anyStrictFired) {
                // Blend 80% strict Mamdani min + 20% mean to break exact ties cleanly
                (0.80 * strictMin + 0.20 * meanDeg).coerceIn(0.0, 1.0)
            } else {
                meanDeg.coerceIn(0.0, 1.0)
            }
            RuleActivation(rule, strictMin, meanDeg, alpha)
        }.sortedByDescending { it.effectiveStrength }

        val evalModeStr = if (anyStrictFired) "Strict Mamdani Min t-norm" else "Compensatory Soft-AND Fallback"
        log.append("\n2. Rule Evaluation ($evalModeStr):\n")

        // --- STAGE 3 & 4: IMPLICATION (Min-Clipping) & AGGREGATION (Max-Union) ---
        // --- STAGE 5: DEFUZZIFICATION (Centroid / Center of Gravity over X and Y) ---
        val hasCoordinates = rules.any { it.coordX != 0.0 || it.coordY != 0.0 }
        val defuzzifiedCoords: Pair<Double, Double>? = if (hasCoordinates) {
            val minX = rules.minOf { it.coordX } - outputSpreadMeters
            val maxX = rules.maxOf { it.coordX } + outputSpreadMeters
            val minY = rules.minOf { it.coordY } - outputSpreadMeters
            val maxY = rules.maxOf { it.coordY } + outputSpreadMeters

            val cogX = defuzzifyAxisCentroid(minX, maxX) { x ->
                var aggregatedMu = 0.0
                for (act in activations) {
                    if (act.effectiveStrength <= 0.0) continue
                    val outTri = FuzzyRssiTerm.triangle(
                        x,
                        act.rule.coordX - outputSpreadMeters,
                        act.rule.coordX,
                        act.rule.coordX + outputSpreadMeters
                    )
                    // Mamdani implication: min(alpha_r, mu_consequent)
                    val clipped = min(act.effectiveStrength, outTri)
                    // Mamdani aggregation: max over all rules
                    aggregatedMu = max(aggregatedMu, clipped)
                }
                aggregatedMu
            }

            val cogY = defuzzifyAxisCentroid(minY, maxY) { y ->
                var aggregatedMu = 0.0
                for (act in activations) {
                    if (act.effectiveStrength <= 0.0) continue
                    val outTri = FuzzyRssiTerm.triangle(
                        y,
                        act.rule.coordY - outputSpreadMeters,
                        act.rule.coordY,
                        act.rule.coordY + outputSpreadMeters
                    )
                    val clipped = min(act.effectiveStrength, outTri)
                    aggregatedMu = max(aggregatedMu, clipped)
                }
                aggregatedMu
            }

            if (cogX != null && cogY != null) Pair(cogX, cogY) else null
        } else {
            null
        }
        lastEstimatedCoords = defuzzifiedCoords

        val rankings = mutableListOf<SpotRanking>()
        for ((idx, act) in activations.withIndex()) {
            val rank = idx + 1
            val marker = if (rank == 1) "🏆 [WINNER]" else "   "
            val alphaStr = String.format(Locale.US, "%.4f", act.effectiveStrength)
            val minStr = String.format(Locale.US, "%.2f", act.strictMinStrength)
            val avgStr = String.format(Locale.US, "%.2f", act.meanStrength)
            log.append("$marker #$rank R${act.rule.ruleId} (${act.rule.spotLabel}) -> α = $alphaStr (min=$minStr, avg=$avgStr)\n")
            rankings.add(
                SpotRanking(
                    rank = rank,
                    spotLabel = act.rule.spotLabel,
                    scoreMetric = act.effectiveStrength,
                    metricLabel = "Firing α",
                    details = "α=$alphaStr (min=$minStr)"
                )
            )
        }

        val winner = activations.first().rule.spotLabel
        val bestAlpha = activations.first().effectiveStrength
        val margin = if (activations.size > 1) {
            bestAlpha - activations[1].effectiveStrength
        } else {
            null
        }

        val marginStr = if (margin != null) " (Margin: +${String.format(Locale.US, "%.4f", margin)})" else ""
        log.append("\n3. Implication (Min-Clip) -> Aggregation (Max-Union) -> COG Defuzzification:\n")
        if (defuzzifiedCoords != null) {
            log.append(
                "   • Defuzzified Centroid: (${String.format(Locale.US, "%.2f", defuzzifiedCoords.first)} m, " +
                        "${String.format(Locale.US, "%.2f", defuzzifiedCoords.second)} m)\n"
            )
        }
        log.append("📍 PREDICTED LOCATION ($shortCode): $winner (α=${String.format(Locale.US, "%.4f", bestAlpha)})$marginStr\n")

        return LocalizationResult(
            strategyName = name,
            predictedSpot = winner,
            confidenceMargin = margin,
            rankings = rankings,
            logOutput = log.toString()
        )
    }

    /**
     * Numerical Center of Gravity (COG / Centroid) defuzzification over a 1D continuous domain:
     *   z* = Σ(z · μ_agg(z)) / Σ(μ_agg(z))
     */
    private fun defuzzifyAxisCentroid(
        minVal: Double,
        maxVal: Double,
        aggregatedMembershipAt: (Double) -> Double
    ): Double? {
        if (maxVal < minVal) return null
        var numerator = 0.0
        var denominator = 0.0
        var z = minVal
        while (z <= maxVal + 1e-6) {
            val mu = aggregatedMembershipAt(z)
            if (mu > 0.0) {
                numerator += z * mu
                denominator += mu
            }
            z += gridStepMeters
        }
        return if (denominator > 1e-9) numerator / denominator else null
    }

    /**
     * Returns a formatted human-readable dump of the linguistic membership definitions
     * and the complete auto-generated Mamdani IF-THEN rule base.
     */
    fun getFormattedRuleBase(): String {
        if (rules.isEmpty()) {
            return "⚠️ Mamdani FIS rule base is empty! Record or load survey spots first."
        }
        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("🧠 MAMDANI FUZZY INFERENCE SYSTEM (FIS)\n")
        sb.append("========================================\n")
        sb.append("Linguistic Input Sets (9-Term Ruspini Partition, dBm):\n")
        sb.append("  • UNHEARD:        Missing or ≤ -90 dBm\n")
        sb.append("  • EXTREMELY_WEAK: [-96, -90, -84] dBm\n")
        sb.append("  • VERY_WEAK:      [-90, -84, -78] dBm\n")
        sb.append("  • WEAK:           [-84, -78, -72] dBm\n")
        sb.append("  • FAIR:           [-78, -72, -66] dBm\n")
        sb.append("  • MODERATE:       [-72, -66, -60] dBm\n")
        sb.append("  • GOOD:           [-66, -60, -54] dBm\n")
        sb.append("  • STRONG:         [-60, -54, -48] dBm\n")
        sb.append("  • VERY_STRONG:    [-54, -48, 0] dBm\n")
        sb.append("Inference:   Mamdani Min-Implication / Max-Aggregation\n")
        sb.append("Defuzzifier: Center of Gravity (COG / Centroid)\n")
        sb.append("Total Rules: ${rules.size} (1 per surveyed spot)\n")
        sb.append("========================================\n\n")

        for (rule in rules) {
            sb.append("${rule.formatRuleText()}\n\n")
        }
        return sb.toString()
    }
}
