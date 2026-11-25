package com.example.wififingerprint

import com.example.wififingerprint.strategy.FuzzyRssiTerm
import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.MamdaniFuzzyStrategy
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MamdaniFuzzyStrategyTest {

    @Test
    fun fuzzificationFormsComplete9TermRuspiniPartition() {
        // Missing AP -> UNHEARD = 1.0
        assertEquals(1.0, FuzzyRssiTerm.UNHEARD.membership(null), 1e-6)
        assertEquals(0.0, FuzzyRssiTerm.VERY_STRONG.membership(null), 1e-6)

        // Verify all 9 peaks
        assertEquals(FuzzyRssiTerm.UNHEARD, FuzzyRssiTerm.dominantTerm(-98))
        assertEquals(FuzzyRssiTerm.EXTREMELY_WEAK, FuzzyRssiTerm.dominantTerm(-90))
        assertEquals(FuzzyRssiTerm.VERY_WEAK, FuzzyRssiTerm.dominantTerm(-84))
        assertEquals(FuzzyRssiTerm.WEAK, FuzzyRssiTerm.dominantTerm(-78))
        assertEquals(FuzzyRssiTerm.FAIR, FuzzyRssiTerm.dominantTerm(-72))
        assertEquals(FuzzyRssiTerm.MODERATE, FuzzyRssiTerm.dominantTerm(-66))
        assertEquals(FuzzyRssiTerm.GOOD, FuzzyRssiTerm.dominantTerm(-60))
        assertEquals(FuzzyRssiTerm.STRONG, FuzzyRssiTerm.dominantTerm(-54))
        assertEquals(FuzzyRssiTerm.VERY_STRONG, FuzzyRssiTerm.dominantTerm(-45))

        // Verify Ruspini partition: sum of memberships == 1.0 for every dBm in [-100, -30]
        for (rssi in -100..-30) {
            val sum = FuzzyRssiTerm.fuzzify(rssi).values.sum()
            assertEquals("Ruspini sum failed at $rssi dBm", 1.0, sum, 1e-6)
        }

        // Boundary overlap: -57 dBm splits 50/50 between GOOD and STRONG
        assertEquals(0.5, FuzzyRssiTerm.GOOD.membership(-57), 1e-6)
        assertEquals(0.5, FuzzyRssiTerm.STRONG.membership(-57), 1e-6)
    }

    @Test
    fun mamdaniInfersCorrectSpotAndDefuzzifiesCoordinates() {
        val spot1 = RawSurveySpot(
            spotLabel = "S1",
            measurements = listOf(
                RawApMeasurement("aa:bb:cc:00:00:01", "AP1", listOf(-46, -47, -45, -48), -46),
                RawApMeasurement("aa:bb:cc:00:00:02", "AP2", listOf(-85, -86, -84, -85), -85)
            ),
            totalScans = 4,
            coordX = 0.0,
            coordY = 0.0
        )
        val spot2 = RawSurveySpot(
            spotLabel = "S2",
            measurements = listOf(
                RawApMeasurement("aa:bb:cc:00:00:01", "AP1", listOf(-73, -72, -74, -73), -73),
                RawApMeasurement("aa:bb:cc:00:00:02", "AP2", listOf(-47, -46, -48, -47), -47)
            ),
            totalScans = 4,
            coordX = 4.0,
            coordY = 2.0
        )

        val strategy = MamdaniFuzzyStrategy()
        strategy.buildModel(listOf(spot1, spot2))

        assertTrue(strategy.isModelLoaded())
        assertEquals(2, strategy.getRules().size)

        // Query clearly at S1
        val queryAtS1 = LiveQueryScan(
            readings = mapOf(
                "aa:bb:cc:00:00:01" to -46,
                "aa:bb:cc:00:00:02" to -85
            )
        )
        val res1 = strategy.infer(queryAtS1)
        assertEquals("S1", res1.predictedSpot)
        val coords1 = strategy.lastEstimatedCoords
        assertNotNull(coords1)
        assertEquals(0.0, coords1!!.first, 0.25)
        assertEquals(0.0, coords1.second, 0.25)

        // Query clearly at S2
        val queryAtS2 = LiveQueryScan(
            readings = mapOf(
                "aa:bb:cc:00:00:01" to -73,
                "aa:bb:cc:00:00:02" to -47
            )
        )
        val res2 = strategy.infer(queryAtS2)
        assertEquals("S2", res2.predictedSpot)
        val coords2 = strategy.lastEstimatedCoords
        assertNotNull(coords2)
        assertEquals(4.0, coords2!!.first, 0.25)
        assertEquals(2.0, coords2.second, 0.25)
    }

    @Test
    fun mamdaniCompensatoryFallbackHandlesOutlierWithoutReturningNull() {
        val spot1 = RawSurveySpot(
            spotLabel = "S1",
            measurements = listOf(
                RawApMeasurement("aa:bb:cc:00:00:01", "AP1", listOf(-45, -46, -45), -45),
                RawApMeasurement("aa:bb:cc:00:00:02", "AP2", listOf(-60, -61, -62), -61)
            ),
            totalScans = 3,
            coordX = 0.0,
            coordY = 0.0
        )
        val spot2 = RawSurveySpot(
            spotLabel = "S2",
            measurements = listOf(
                RawApMeasurement("aa:bb:cc:00:00:01", "AP1", listOf(-85, -86, -84), -85),
                RawApMeasurement("aa:bb:cc:00:00:02", "AP2", listOf(-84, -85, -86), -85)
            ),
            totalScans = 3,
            coordX = 6.0,
            coordY = 0.0
        )

        val strategy = MamdaniFuzzyStrategy()
        strategy.buildModel(listOf(spot1, spot2))

        // AP1 matches S1 strongly (-45), but AP2 has an unexpected huge dropout (-90)
        // Strict min would be 0.0 for all rules; fallback must still predict S1 cleanly.
        val noisyQuery = LiveQueryScan(
            readings = mapOf(
                "aa:bb:cc:00:00:01" to -45,
                "aa:bb:cc:00:00:02" to -90
            )
        )
        val res = strategy.infer(noisyQuery)
        assertNotNull(res.predictedSpot)
        assertEquals("S1", res.predictedSpot)
        assertTrue(strategy.getFormattedRuleBase().contains("MAMDANI FUZZY INFERENCE SYSTEM"))
    }
}
