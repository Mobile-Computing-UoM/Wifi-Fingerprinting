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
        fuzzyStrategy = FuzzyMatrixStrategy(useNeighbourSmoothing = true)

        // 1. Hall survey (100 total scans):
        // AP1: 60 scans at -67 (raw μ = 0.60), 40 scans not heard (NS)
        val hallAp1Readings = MutableList(60) { -67 }
        // AP2: 30 scans at -73 (raw μ = 0.30), 70 scans not heard (NS)
        val hallAp2Readings = MutableList(30) { -73 }
        // AP3: 30 scans at -90 (raw μ = 0.30), 70 scans not heard (NS)
        val hallAp3Readings = MutableList(30) { -90 }
        // AP4: Never heard (0 readings out of 100 scans -> NS frequency = 1.0)
        val hallSpot = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", hallAp1Readings, -67),
                RawApMeasurement("ap2", "AP2", hallAp2Readings, -73),
                RawApMeasurement("ap3", "AP3", hallAp3Readings, -90)
            ),
            totalScans = 100
        )

        // 2. Kitchen survey (100 total scans):
        // Very strong AP1 at -40 dBm, AP2 at -50 dBm, AP4 at -55 dBm
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
        // AP2: 60 scans at -75 (raw μ = 0.60), 40 scans not heard (NS)
        // AP4: 50 scans at -90, 50 scans not heard (NS frequency = 0.50)
        val labSpot = RawSurveySpot(
            spotLabel = "Lab",
            measurements = listOf(
                RawApMeasurement("ap2", "AP2", MutableList(60) { -75 }, -75),
                RawApMeasurement("ap4", "AP4", MutableList(50) { -90 }, -90)
            ),
            totalScans = 100
        )

        sampleSurveys = listOf(hallSpot, kitchenSpot, labSpot)
        knnStrategy.buildModel(sampleSurveys)
        fuzzyStrategy.buildModel(sampleSurveys)
    }

    @Test
    fun testQueryD_neighbourSmoothing_exactUserExample() {
        // Query D: [-68, -74, -91, NS], all 1 dB off Hall's stored values:
        // AP1 = -68 (stored: -67 with μ=0.60) -> smoothed μ = 0.30
        // AP2 = -74 (stored: -73 with μ=0.30) -> smoothed μ = 0.15
        // AP3 = -91 (stored: -90 with μ=0.30) -> smoothed μ = 0.15
        // AP4 = NS  (Hall NS frequency = 1.0) -> smoothed μ = 1.00
        // Average Hall = (0.30 + 0.15 + 0.15 + 1.0) / 4 = 0.40
        //
        // Lab:
        // AP1 = -68 (stored: none)            -> smoothed μ = 0.00
        // AP2 = -74 (stored: -75 with μ=0.60) -> smoothed μ = 0.30
        // AP3 = -91 (stored: none)            -> smoothed μ = 0.00
        // AP4 = NS  (Lab NS frequency = 0.50) -> smoothed μ = 0.50
        // Average Lab = (0.00 + 0.30 + 0.00 + 0.50) / 4 = 0.20
        //
        // Hall wins with 0.40 vs Lab 0.20 (Winning Margin: +0.20)
        val queryD = LiveQueryScan(
            readings = mapOf("ap1" to -68, "ap2" to -74, "ap3" to -91)
        )

        val fuzzyResult = fuzzyStrategy.infer(queryD)
        assertEquals("Hall", fuzzyResult.predictedSpot)
        assertNotNull(fuzzyResult.confidenceMargin)

        val hallRanking = fuzzyResult.rankings.first { it.spotLabel == "Hall" }
        assertEquals(0.40, hallRanking.scoreMetric, 0.001)

        val labRanking = fuzzyResult.rankings.first { it.spotLabel == "Lab" }
        assertEquals(0.20, labRanking.scoreMetric, 0.001)

        assertEquals(0.20, fuzzyResult.confidenceMargin!!, 0.001)
    }

    @Test
    fun testExactLookupWithoutSmoothing_failsToMatchQueryD() {
        val rawFuzzyStrategy = FuzzyMatrixStrategy(useNeighbourSmoothing = false)
        rawFuzzyStrategy.buildModel(sampleSurveys)

        val queryD = LiveQueryScan(
            readings = mapOf("ap1" to -68, "ap2" to -74, "ap3" to -91)
        )

        val result = rawFuzzyStrategy.infer(queryD)
        // Without smoothing, exact integer lookup yields 0 for AP1, AP2, AP3 in Hall!
        // Hall only gets 1.0 for AP4 (NS) -> Average = 1.0 / 4 = 0.25
        val hallRanking = result.rankings.first { it.spotLabel == "Hall" }
        assertEquals(0.25, hallRanking.scoreMetric, 0.001)

        // Lab gets 0 for AP1, AP2, AP3 and 0.5 for AP4 (NS) -> Average = 0.5 / 4 = 0.125
        val labRanking = result.rankings.first { it.spotLabel == "Lab" }
        assertEquals(0.125, labRanking.scoreMetric, 0.001)
    }

    @Test
    fun testStandardQuery_bothStrategiesAgreeOnHall() {
        val queryA = LiveQueryScan(
            readings = mapOf("ap1" to -67, "ap2" to -73, "ap3" to -90)
        )

        // Test k-NN
        val knnResult = knnStrategy.infer(queryA)
        assertEquals("Hall", knnResult.predictedSpot)

        // Test Fuzzy Matrix
        val fuzzyResult = fuzzyStrategy.infer(queryA)
        assertEquals("Hall", fuzzyResult.predictedSpot)

        // Hall scores: AP1(-67)=0.60, AP2(-73)=0.30, AP3(-90)=0.30, AP4(NS)=1.00 -> Avg = 2.20 / 4 = 0.55
        val hallRanking = fuzzyResult.rankings.first { it.spotLabel == "Hall" }
        assertEquals(0.55, hallRanking.scoreMetric, 0.001)
        assertTrue(fuzzyResult.confidenceMargin!! > 0.0)
    }

    @Test
    fun testScanLevelKnn_kVotingAndMetricCoordinates() {
        // Test k=3 majority voting and 2D coordinate estimation across individual stored scans
        val knn3 = KnnStrategy(k = 3, maxSamplesPerSpot = 30)
        knn3.buildModel(sampleSurveys)

        val queryNearHall = LiveQueryScan(
            readings = mapOf("ap1" to -68, "ap2" to -74, "ap3" to -90)
        )
        val res = knn3.infer(queryNearHall)
        assertEquals("Hall", res.predictedSpot)
        assertNotNull(knn3.lastEstimatedCoords)

        // Hall ground truth is (2.5, 1.0); error to Hall should be 0.0m
        val errToHall = KnnStrategy.computeErrorMeters("Hall", res.predictedSpot, knn3.lastEstimatedCoords)
        assertNotNull(errToHall)
        assertEquals(0.0, errToHall!!, 0.01)

        // Error if actual spot was Kitchen (7.0, 3.0) should be > 4.0m
        val errToKitchen = KnnStrategy.computeErrorMeters("Kitchen", res.predictedSpot, knn3.lastEstimatedCoords)
        assertNotNull(errToKitchen)
        assertTrue(errToKitchen!! > 4.0)
    }
}
