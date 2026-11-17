package com.example.wififingerprint

import com.example.wififingerprint.data.FingerprintFile
import com.example.wififingerprint.data.FingerprintFileMetadata
import com.example.wififingerprint.experiment.ExperimentCsvManager
import com.example.wififingerprint.experiment.ExperimentIterationResult
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentUnitTest {

    @Test
    fun testExperimentIterationResultSerializationAndParsing() {
        val signals = mapOf("anon_1122" to -55, "anon_3344" to -70)
        val result = ExperimentIterationResult.create(
            separationMeters = 2.0,
            trialIndex = 1,
            totalTrials = 20,
            locateScans = 10,
            actualSpot = "S1",
            actualX = 0.0,
            actualY = 0.0,
            knnPredSpot = "S1",
            knnPredX = 0.0,
            knnPredY = 0.0,
            knnErrorMeters = 0.0,
            fuzzyPredSpot = "S1",
            fuzzyPredX = 0.0,
            fuzzyPredY = 0.0,
            fuzzyErrorMeters = 0.0,
            rawSignalsMap = signals
        )

        assertTrue(result.knnCorrect)
        assertTrue(result.fuzzyCorrect)
        assertEquals(0.0, result.knnErrorMeters, 0.001)

        // Parse from raw CSV line
        val parsed = ExperimentCsvManager.parseLine(result.rawCsvLine)
        assertNotNull(parsed)
        assertEquals(result.trialIndex, parsed!!.trialIndex)
        assertEquals(result.totalTrials, parsed.totalTrials)
        assertEquals(result.actualSpot, parsed.actualSpot)
        assertEquals(result.separationMeters, parsed.separationMeters, 0.01)
        assertEquals(result.knnPredSpot, parsed.knnPredSpot)
        assertEquals(result.fuzzyPredSpot, parsed.fuzzyPredSpot)
        assertTrue(parsed.knnCorrect)
        assertTrue(parsed.fuzzyCorrect)
    }

    @Test
    fun testFingerprintFileCoordinatesSerialization() {
        val spot = RawSurveySpot(
            spotLabel = "S3",
            measurements = listOf(
                RawApMeasurement("aa:bb:cc:dd:ee:01", "Lab-AP", listOf(-60, -62, -61), -61)
            ),
            totalScans = 3,
            coordX = 4.0,
            coordY = 1.5
        )

        val meta = FingerprintFileMetadata(deviceId = "test-device")
        val serialized = FingerprintFile.serialize(meta, listOf(spot))
        assertTrue(serialized.contains("S3,4.0,1.5"))

        val (_, parsedSpots) = FingerprintFile.parse(serialized)
        assertEquals(1, parsedSpots.size)
        assertEquals("S3", parsedSpots[0].spotLabel)
        assertEquals(4.0, parsedSpots[0].coordX, 0.01)
        assertEquals(1.5, parsedSpots[0].coordY, 0.01)
    }

    @Test
    fun testFingerprintFileLegacyBackwardCompatibility() {
        // Legacy 7-column CSV without coords
        val legacyCsv = """
            #fp_version=1
            #device_id=dev1
            timestamp,spot_label,bssid,ssid,sample_count,samples,avg_rssi
            1700000000000,S1,aa:bb:cc:dd:ee:01,AP1,3,"[-60;-62;-61]",-61
        """.trimIndent()

        val (_, parsedSpots) = FingerprintFile.parse(legacyCsv)
        assertEquals(1, parsedSpots.size)
        assertEquals("S1", parsedSpots[0].spotLabel)
        assertEquals(0.0, parsedSpots[0].coordX, 0.01)
        assertEquals(0.0, parsedSpots[0].coordY, 0.01)
    }
}

