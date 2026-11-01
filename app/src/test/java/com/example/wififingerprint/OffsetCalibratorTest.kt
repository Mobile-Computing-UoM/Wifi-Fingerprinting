package com.example.wififingerprint

import com.example.wififingerprint.calibration.OffsetCalibrator
import com.example.wififingerprint.strategy.KnnStrategy
import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetCalibratorTest {

    @Test
    fun testCompute_uniformOffset() {
        val spot = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-50), -50),
                RawApMeasurement("ap2", "AP2", listOf(-65), -65),
                RawApMeasurement("ap3", "AP3", listOf(-80), -80)
            ),
            totalScans = 10
        )

        // Live scan has exact +7 dB offset: AP1=-43, AP2=-58, AP3=-73
        val liveScan = mapOf(
            "ap1" to -43,
            "ap2" to -58,
            "ap3" to -73
        )

        val result = OffsetCalibrator.compute(liveScan, spot, minSharedAps = 3)
        assertTrue(result.isValid)
        assertEquals(7.0, result.medianOffsetDb, 0.001)
        assertEquals(7.0, result.meanOffsetDb, 0.001)
        assertEquals(0.0, result.stdDevDb, 0.001)
        assertEquals(3, result.sharedApCount)
    }

    @Test
    fun testCompute_outlierRejectionUsingMedian() {
        val spot = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-50), -50),
                RawApMeasurement("ap2", "AP2", listOf(-65), -65),
                RawApMeasurement("ap3", "AP3", listOf(-80), -80)
            ),
            totalScans = 10
        )

        // AP1 and AP2 have +7 dB offset, but AP3 has an outlier spike of +21 dB
        val liveScan = mapOf(
            "ap1" to -43, // diff = +7
            "ap2" to -58, // diff = +7
            "ap3" to -59  // diff = +21 (outlier)
        )

        val result = OffsetCalibrator.compute(liveScan, spot, minSharedAps = 3)
        assertTrue(result.isValid)
        // Median isolates the true +7 dB offset, rejecting the +21 spike
        assertEquals(7.0, result.medianOffsetDb, 0.001)
        assertTrue(result.meanOffsetDb > 10.0) // Mean is distorted by outlier
    }

    @Test
    fun testCompute_insufficientSharedAps() {
        val spot = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-50), -50),
                RawApMeasurement("ap2", "AP2", listOf(-65), -65)
            ),
            totalScans = 10
        )

        val liveScan = mapOf("ap1" to -43, "ap2" to -58)
        val result = OffsetCalibrator.compute(liveScan, spot, minSharedAps = 3)

        assertFalse(result.isValid)
        assertNotNull(result.warningMessage)
    }

    @Test
    fun testCalibratedKnn_hallVsHallwayResolution() {
        // Demonstrate the user's specific problem:
        // Hall: AP1=-40, AP2=-55 (diff = 15 dB)
        // Hallway 2: AP1=-65, AP2=-80 (diff = 15 dB!)
        val hallSpot = RawSurveySpot(
            spotLabel = "Hall",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-40), -40),
                RawApMeasurement("ap2", "AP2", listOf(-55), -55),
                RawApMeasurement("ap3", "AP3", listOf(-70), -70)
            ),
            totalScans = 10
        )
        val hallway2Spot = RawSurveySpot(
            spotLabel = "Hallway 2",
            measurements = listOf(
                RawApMeasurement("ap1", "AP1", listOf(-65), -65),
                RawApMeasurement("ap2", "AP2", listOf(-80), -80),
                RawApMeasurement("ap3", "AP3", listOf(-95), -95)
            ),
            totalScans = 10
        )

        val knn = KnnStrategy(k = 1)
        knn.buildModel(listOf(hallSpot, hallway2Spot))

        // Phone B standing in Hall with +8 dB hardware boost:
        // AP1 = -40 + 8 = -32
        // AP2 = -55 + 8 = -47
        // AP3 = -70 + 8 = -62
        val rawQueryInHall = LiveQueryScan(mapOf("ap1" to -32, "ap2" to -47, "ap3" to -62))

        // Uncalibrated k-NN has 8 dB error per AP:
        val uncalibratedResult = knn.infer(rawQueryInHall)
        assertEquals("Hall", uncalibratedResult.predictedSpot)

        // Now compute single-spot calibration offset at Hall:
        val calibResult = OffsetCalibrator.compute(rawQueryInHall.readings, hallSpot)
        assertEquals(8.0, calibResult.medianOffsetDb, 0.001)

        // Apply calibration to live query scan:
        val correctedQuery = OffsetCalibrator.apply(rawQueryInHall, calibResult.medianOffsetDb)
        assertEquals(-40, correctedQuery.readings["ap1"])
        assertEquals(-55, correctedQuery.readings["ap2"])
        assertEquals(-70, correctedQuery.readings["ap3"])

        // Calibrated k-NN now has 0.0 dB error!
        val calibratedResult = knn.infer(correctedQuery)
        assertEquals("Hall", calibratedResult.predictedSpot)
        val top1 = calibratedResult.rankings.first()
        assertEquals(0.0, top1.scoreMetric, 0.001)
    }
}
