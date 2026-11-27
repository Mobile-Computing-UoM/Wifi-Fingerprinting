package com.example.wififingerprint

import com.example.wififingerprint.strategy.HybridEnsembleStrategy
import com.example.wififingerprint.strategy.KnnStrategy
import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.MamdaniFuzzyStrategy
import com.example.wififingerprint.strategy.PatternStrategy
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import com.example.wififingerprint.strategy.StrategyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridEnsembleStrategyTest {

    private fun sampleSurveys(): List<RawSurveySpot> {
        val hall = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", List(20) { -48 }, -48),
                RawApMeasurement("ap2", "AP2", List(20) { -72 }, -72),
                RawApMeasurement("ap3", "AP3", List(20) { -84 }, -84)
            ),
            totalScans = 20,
            coordX = 2.0,
            coordY = 1.0
        )
        val bedroom = RawSurveySpot(
            spotLabel = "Bedroom",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", List(20) { -78 }, -78),
                RawApMeasurement("ap2", "AP2", List(20) { -54 }, -54),
                RawApMeasurement("ap3", "AP3", List(20) { -78 }, -78)
            ),
            totalScans = 20,
            coordX = 6.0,
            coordY = 4.0
        )
        val kitchen = RawSurveySpot(
            spotLabel = "Kitchen",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", List(20) { -84 }, -84),
                RawApMeasurement("ap2", "AP2", List(20) { -84 }, -84),
                RawApMeasurement("ap3", "AP3", List(20) { -48 }, -48)
            ),
            totalScans = 20,
            coordX = 9.0,
            coordY = 2.0
        )
        return listOf(hall, bedroom, kitchen)
    }

    @Test
    fun testAgreementCase_highConfidenceAndBlendedCoords() {
        val knn = KnnStrategy(k = 3)
        val mamdani = MamdaniFuzzyStrategy()
        val hybrid = HybridEnsembleStrategy(knn, mamdani)
        hybrid.buildModel(sampleSurveys())

        val query = LiveQueryScan(readings = mapOf("ap1" to -49, "ap2" to -71, "ap3" to -85))
        val res = hybrid.infer(query)

        assertEquals("Hall", res.predictedSpot)
        assertTrue("Confidence should be high on clear match", hybrid.lastConfidencePercent >= 65)
        assertNotNull(res.confidenceMargin)
        assertTrue(res.confidenceMargin!! > 0.2)
        assertNotNull(hybrid.lastEstimatedCoords)
        assertEquals(2.0, hybrid.lastEstimatedCoords!!.first, 0.5)
        assertEquals(1.0, hybrid.lastEstimatedCoords!!.second, 0.5)
        val totalProb = res.rankings.sumOf { it.scoreMetric }
        assertEquals(100.0, totalProb, 0.5)
    }

    @Test
    fun testDisagreementAndTieBreakNormalization() {
        val knn = KnnStrategy(k = 3)
        val mamdani = MamdaniFuzzyStrategy()
        val pattern = PatternStrategy(minSharedAps = 2)
        val hybrid = HybridEnsembleStrategy(knn, mamdani, pattern, useCrossDeviceBoost = true)
        hybrid.buildModel(sampleSurveys())

        // Cross-device shifted (+8 dB stronger across all APs for Bedroom)
        val shiftedQuery = LiveQueryScan(readings = mapOf("ap1" to -70, "ap2" to -46, "ap3" to -70))
        val res = hybrid.infer(shiftedQuery)

        assertEquals("Bedroom", res.predictedSpot)
        assertEquals(3, res.rankings.size)
        val sumPct = res.rankings.sumOf { it.scoreMetric }
        assertEquals(100.0, sumPct, 0.5)
        assertTrue(res.rankings.first().details.contains("Pattern:"))
    }

    @Test
    fun testEmptyModelAndEmptyQuery_gracefulFallback() {
        val hybrid = HybridEnsembleStrategy(KnnStrategy(k = 3), MamdaniFuzzyStrategy())

        val emptyModelRes = hybrid.infer(LiveQueryScan(mapOf("ap1" to -55)))
        assertNull(emptyModelRes.predictedSpot)
        assertTrue(emptyModelRes.rankings.isEmpty())

        hybrid.buildModel(sampleSurveys())
        val emptyQueryRes = hybrid.infer(LiveQueryScan(emptyMap()))
        assertNull(emptyQueryRes.predictedSpot)
        assertEquals(0, hybrid.lastConfidencePercent)
    }

    @Test
    fun testStrategyModeDefaultsToHybrid() {
        assertEquals(StrategyMode.HYBRID, StrategyMode.fromKey(null))
        assertEquals(StrategyMode.HYBRID, StrategyMode.fromKey("UNKNOWN_KEY"))
        assertEquals(StrategyMode.HYBRID, StrategyMode.fromKey("HYBRID"))
    }
}
