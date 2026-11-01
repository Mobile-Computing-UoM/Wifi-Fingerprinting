package com.example.wififingerprint

import com.example.wififingerprint.data.FingerprintFile
import com.example.wififingerprint.data.FingerprintFileMetadata
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintFileTest {

    @Test
    fun testSerializationAndParsingRoundTrip() {
        val originalMeta = FingerprintFileMetadata(
            version = 1,
            deviceId = "oppo_a54s_test",
            model = "OPPO CPH2273",
            sdk = 31,
            exportedAt = 1700000000000L,
            calibA = 1.05,
            calibB = -2.3
        )

        val spots = listOf(
            RawSurveySpot(
                spotLabel = "Hall",
                measurements = listOf(
                    RawApMeasurement("98:a9:42:59:92:85", "SLT-4G_599285", listOf(-50, -52, -51), -51),
                    RawApMeasurement("anon_1234567890ab", "Anon-AP", listOf(-70, -71), -71)
                ),
                totalScans = 3
            ),
            RawSurveySpot(
                spotLabel = "Kitchen",
                measurements = listOf(
                    RawApMeasurement("98:a9:42:0b:16:4a", "Dialog 4G 610", listOf(-60, -62), -61)
                ),
                totalScans = 2
            )
        )

        val serialized = FingerprintFile.serialize(originalMeta, spots)

        assertTrue(serialized.contains("#fp_version=1"))
        assertTrue(serialized.contains("#device_id=oppo_a54s_test"))
        assertTrue(serialized.contains("#model=OPPO CPH2273"))
        // Check that plain MAC was hashed
        assertTrue(!serialized.contains("98:a9:42:59:92:85"))
        assertTrue(serialized.contains("anon_"))

        val (parsedMeta, parsedSpots) = FingerprintFile.parse(serialized)

        assertEquals(1, parsedMeta.version)
        assertEquals("oppo_a54s_test", parsedMeta.deviceId)
        assertEquals("OPPO CPH2273", parsedMeta.model)
        assertEquals(31, parsedMeta.sdk)
        assertEquals(1700000000000L, parsedMeta.exportedAt)
        assertEquals(1.05, parsedMeta.calibA, 0.001)
        assertEquals(-2.3, parsedMeta.calibB, 0.001)

        assertEquals(2, parsedSpots.size)
        val hall = parsedSpots.first { it.spotLabel == "Hall" }
        assertEquals(2, hall.measurements.size)
    }

    @Test
    fun testParseLegacyFormatWithoutHeaders() {
        val legacyCsv = """
            timestamp,spot_label,bssid,ssid,sample_count,samples,avg_rssi
            1791989237302,bedroom,98:a9:42:59:92:85,SLT-4G,3,"[-30;-32;-31]",-31
        """.trimIndent()

        val (meta, spots) = FingerprintFile.parse(legacyCsv)
        assertEquals("unknown", meta.deviceId)
        assertEquals(1, spots.size)
        assertEquals("bedroom", spots[0].spotLabel)
        assertTrue(spots[0].measurements[0].bssid.startsWith("anon_"))
        assertEquals(-31, spots[0].measurements[0].avgRssi)
    }
}
