package com.example.wififingerprint

import com.example.wififingerprint.feedback.FeedbackCsv
import com.example.wififingerprint.feedback.FeedbackHistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackCsvTest {

    @Test
    fun testParseLegacyTenColumnLine() {
        val legacyLine = "1700000000000,2026-11-01T12:00:00Z,Hall,Hall,1,2.5,Hall,1,0.30,\"-50:anon_ap1;-60:anon_ap2\""
        val record = FeedbackCsv.parseLine(legacyLine)

        assertNotNull(record)
        assertEquals(1700000000000L, record!!.timestampMs)
        assertEquals("Hall", record.actualSpot)
        assertEquals("Hall", record.knnPredicted)
        assertTrue(record.knnCorrect)
        assertEquals("Hall", record.fuzzyPredicted)
        assertTrue(record.fuzzyCorrect)
        assertEquals("-50:anon_ap1;-60:anon_ap2", record.querySignals)

        // Defaults for extended fields
        assertEquals("", record.patternPredicted)
        assertEquals("LOCAL", record.mapSource)
        assertEquals(null, record.calibOffsetDb)
    }

    @Test
    fun testSerializeAndParseNewSeventeenColumnFormat() {
        val original = FeedbackHistoryRecord(
            timestampMs = 1791989000000L,
            isoTime = "2026-11-01T21:00:00Z",
            actualSpot = "Bedroom",
            knnPredicted = "Hall",
            knnCorrect = false,
            knnMargin = 1.2,
            fuzzyPredicted = "Hall",
            fuzzyCorrect = false,
            fuzzyMargin = 0.05,
            querySignals = "anon_1:-45;anon_2:-55",
            patternPredicted = "Bedroom",
            patternCorrect = true,
            patternMargin = 3.5,
            mapSource = "IMPORTED",
            refDeviceModel = "OPPO CPH2273",
            locateDeviceModel = "samsung SM-A202F",
            calibOffsetDb = 6.5
        )

        val csvLine = FeedbackCsv.serialize(original)
        val parsed = FeedbackCsv.parseLine(csvLine)

        assertNotNull(parsed)
        assertEquals("Bedroom", parsed!!.actualSpot)
        assertEquals("Hall", parsed.knnPredicted)
        assertFalse(parsed.knnCorrect)
        assertEquals("Bedroom", parsed.patternPredicted)
        assertTrue(parsed.patternCorrect)
        assertEquals(3.5, parsed.patternMargin, 0.01)
        assertEquals("IMPORTED", parsed.mapSource)
        assertEquals("OPPO CPH2273", parsed.refDeviceModel)
        assertEquals("samsung SM-A202F", parsed.locateDeviceModel)
        assertEquals(6.5, parsed.calibOffsetDb!!, 0.01)
    }
}
