package com.example.wififingerprint

import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.PatternFeatures
import com.example.wififingerprint.strategy.PatternStrategy
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PatternStrategyUnitTest {

    private lateinit var patternStrategy: PatternStrategy
    private lateinit var sampleSurveys: List<RawSurveySpot>

    @Before
    fun setUp() {
        patternStrategy = PatternStrategy(minSharedAps = 2)

        // Spot 1: Hall
        // AP1 = -50 dBm, AP2 = -65 dBm, AP3 = -80 dBm
        val hallSpot = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-50), -50),
                RawApMeasurement("ap2", "AP2", listOf(-65), -65),
                RawApMeasurement("ap3", "AP3", listOf(-80), -80)
            ),
            totalScans = 10
        )

        // Spot 2: Kitchen
        // AP1 = -75 dBm, AP2 = -52 dBm, AP3 = -60 dBm
        val kitchenSpot = RawSurveySpot(
            spotLabel = "Kitchen",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-75), -75),
                RawApMeasurement("ap2", "AP2", listOf(-52), -52),
                RawApMeasurement("ap3", "AP3", listOf(-60), -60)
            ),
            totalScans = 10
        )

        // Spot 3: Bedroom
        // AP1 = -85 dBm, AP2 = -85 dBm, AP3 = -45 dBm
        val bedroomSpot = RawSurveySpot(
            spotLabel = "Bedroom",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-85), -85),
                RawApMeasurement("ap2", "AP2", listOf(-85), -85),
                RawApMeasurement("ap3", "AP3", listOf(-45), -45)
            ),
            totalScans = 10
        )

        sampleSurveys = listOf(hallSpot, kitchenSpot, bedroomSpot)
        patternStrategy.buildModel(sampleSurveys)
    }

    @Test
    fun testPairwiseDifference_exactHardwareShiftInvariance() {
        // Query has +7 dB hardware antenna offset from Hall:
        // AP1: -50 + 7 = -43
        // AP2: -65 + 7 = -58
        // AP3: -80 + 7 = -73
        val pairs = listOf(
            Pair(-43, -50),
            Pair(-58, -65),
            Pair(-73, -80)
        )

        val pairwiseErr = PatternFeatures.meanPairwiseDifference(pairs)
        val meanCenteredErr = PatternFeatures.meanCenteredDistance(pairs)
        val rankErr = PatternFeatures.rankDistance(pairs)

        assertEquals(0.0, pairwiseErr, 0.0001)
        assertEquals(0.0, meanCenteredErr, 0.0001)
        assertEquals(0.0, rankErr, 0.0001)
    }

    @Test
    fun testPatternStrategy_crossDeviceShift_correctLocalization() {
        // Test Phone (e.g. Samsung A20e) querying in Hall with a large +8 dB antenna sensitivity difference
        val queryInHallShifted = LiveQueryScan(
            readings = mapOf(
                "ap1" to -42, // Hall stored -50 (+8 dB)
                "ap2" to -57, // Hall stored -65 (+8 dB)
                "ap3" to -72  // Hall stored -80 (+8 dB)
            )
        )

        val result = patternStrategy.infer(queryInHallShifted)
        assertEquals("Hall", result.predictedSpot)
        assertNotNull(result.confidenceMargin)
        assertTrue(result.confidenceMargin!! > 2.0)

        // Hall ranking should have error near 0.0 dB
        val top1 = result.rankings.first()
        assertEquals("Hall", top1.spotLabel)
        assertEquals(0.0, top1.scoreMetric, 0.01)
    }

    @Test
    fun testPatternStrategy_kitchenWithNonLinearNoise() {
        // Test Phone querying in Kitchen:
        // Kitchen stored: AP1=-75, AP2=-52, AP3=-60
        // Query has +5 dB offset plus small ±1 dB measurement noise
        val queryKitchenWithNoise = LiveQueryScan(
            readings = mapOf(
                "ap1" to -70, // -75 + 5
                "ap2" to -46, // -52 + 6 (+1 dB noise)
                "ap3" to -55  // -60 + 5
            )
        )

        val result = patternStrategy.infer(queryKitchenWithNoise)
        assertEquals("Kitchen", result.predictedSpot)
        assertTrue(result.rankings[0].scoreMetric < result.rankings[1].scoreMetric)
    }
}
