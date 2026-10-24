package com.example.wififingerprint

import com.example.wififingerprint.strategy.FuzzyMatrixStrategy
import com.example.wififingerprint.strategy.KnnStrategy
import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StrategyPatternUnitTest {

    private lateinit var sampleSurveys: List<RawSurveySpot>
    private lateinit var knnStrategy: KnnStrategy
    private lateinit var fuzzyStrategy: FuzzyMatrixStrategy

    @Before
    fun setUp() {
        knnStrategy = KnnStrategy(k = 1)
        fuzzyStrategy = FuzzyMatrixStrategy()

        // 1. Hall survey (100 total scans):
        // AP1: 60 scans at -67, 25 scans at -65, 15 scans at -66 -> avg approx -66
        val hallAp1Readings = MutableList(60) { -67 } + MutableList(25) { -65 } + MutableList(15) { -66 }
        // AP2: 30 scans at -73, 70 scans at -75
        val hallAp2Readings = MutableList(30) { -73 } + MutableList(70) { -75 }
        // AP3: 25 scans at -89, 75 scans at -90
        val hallAp3Readings = MutableList(25) { -89 } + MutableList(75) { -90 }
        // AP4: Never heard (0 readings out of 100 scans -> NS frequency = 1.0)
        val hallSpot = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", hallAp1Readings, -66),
                RawApMeasurement("ap2", "AP2", hallAp2Readings, -74),
                RawApMeasurement("ap3", "AP3", hallAp3Readings, -90)
            ),
            totalScans = 100
        )

        // 2. Kitchen survey (100 total scans):
        // Very strong AP1 at -40 dBm, AP4 at -55 dBm
        val kitchenSpot = RawSurveySpot(
            spotLabel = "Kitchen",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", MutableList(100) { -40 }, -40),
                RawApMeasurement("ap2", "AP2", MutableList(100) { -50 }, -50),
                RawApMeasurement("ap4", "AP4", MutableList(100) { -55 }, -55)
            ),
            totalScans = 100
        )

        // 3. Lab survey (100 total scans):
        // AP4 heard 50 scans at -90, 50 scans not heard (NS)
        val labSpot = RawSurveySpot(
            spotLabel = "Lab",
            measurements = listOf(
                RawApMeasurement("ap4", "AP4", MutableList(50) { -90 }, -90)
            ),
            totalScans = 100
        )

        sampleSurveys = listOf(hallSpot, kitchenSpot, labSpot)
        knnStrategy.buildModel(sampleSurveys)
        fuzzyStrategy.buildModel(sampleSurveys)
    }

    @Test
    fun testStandardQuery_bothStrategiesAgreeOnHall() {
        // Query A: AP1 = -66, AP2 = -73, AP3 = -89, AP4 not heard (absent)
        val queryA = LiveQueryScan(
            readings = mapOf("ap1" to -66, "ap2" to -73, "ap3" to -89)
        )

        // Test k-NN
        val knnResult = knnStrategy.infer(queryA)
        assertEquals("Hall", knnResult.predictedSpot)
        assertNotNull(knnResult.confidenceMargin)

        // Test Fuzzy Matrix Strategy
        val fuzzyResult = fuzzyStrategy.infer(queryA)
        assertEquals("Hall", fuzzyResult.predictedSpot)
        assertNotNull(fuzzyResult.confidenceMargin)
        assertTrue("Winning margin must be positive", fuzzyResult.confidenceMargin!! > 0.0)

        // In Hall (tolerance bandwidth Δ = 3.5 dBm):
        // AP1(-66): 60*(-67: dist 1 => 5/7) + 25*(-65: dist 1 => 5/7) + 15*(-66: dist 0 => 1.0) = 75.71 / 100 = 0.7571
        // AP2(-73): 30*(-73: dist 0 => 1.0) + 70*(-75: dist 2 => 3/7) = 60.0 / 100 = 0.6000
        // AP3(-89): 25*(-89: dist 0 => 1.0) + 75*(-90: dist 1 => 5/7) = 78.57 / 100 = 0.7857
        // Relevant active APs = [ap1, ap2, ap3]
        // Average = (0.7571 + 0.6000 + 0.7857) / 3 ≈ 0.7143
        val hallRanking = fuzzyResult.rankings.first { it.spotLabel == "Hall" }
        assertEquals(0.7143, hallRanking.scoreMetric, 0.005)
    }

    @Test
    fun testDeepFadeTolerance_fuzzyMatrixDoesNotCollapse() {
        // AP3 experiences deep fade to -97 dBm (never seen in training for any spot!)
        val queryDeepFade = LiveQueryScan(
            readings = mapOf("ap1" to -66, "ap2" to -73, "ap3" to -97)
        )

        val fuzzyResult = fuzzyStrategy.infer(queryDeepFade)
        // Hall must still win despite AP3 having 0.0 membership!
        assertEquals("Hall", fuzzyResult.predictedSpot)

        // In Hall:
        // AP1(-66): 0.7571
        // AP2(-73): 0.6000
        // AP3(-97): 0.0000 (Distance to [-89, -90] is > 3.5 dBm -> drops to 0.0)
        // Average = (0.7571 + 0.6000 + 0.0000) / 3 ≈ 0.4524
        val hallRanking = fuzzyResult.rankings.first { it.spotLabel == "Hall" }
        assertEquals(0.4524, hallRanking.scoreMetric, 0.005)

        // Lab active APs: [ap1, ap2, ap3, ap4] -> AP4 NS=0.50, others 0.0 -> Avg = 0.50 / 4 = 0.125
        val labRanking = fuzzyResult.rankings.first { it.spotLabel == "Lab" }
        assertEquals(0.125, labRanking.scoreMetric, 0.005)

        // Winning Margin = 0.4524 - 0.125 = 0.3274
        assertEquals(0.3274, fuzzyResult.confidenceMargin!!, 0.005)
    }
}
