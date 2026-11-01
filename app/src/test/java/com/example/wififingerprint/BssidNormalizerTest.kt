package com.example.wififingerprint

import com.example.wififingerprint.data.BssidNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BssidNormalizerTest {

    @Test
    fun testNormalizeBssid_deterministic() {
        val mac1 = "98:a9:42:59:92:85"
        val mac1Upper = "98:A9:42:59:92:85"
        val hash1 = BssidNormalizer.normalizeBssid(mac1)
        val hash2 = BssidNormalizer.normalizeBssid(mac1Upper)

        assertTrue(hash1.startsWith("anon_"))
        assertEquals(hash1, hash2)
        assertEquals(17, hash1.length) // "anon_" (5) + 12 hex chars = 17
    }

    @Test
    fun testNormalizeBssid_alreadyAnonymized_preserved() {
        val anon = "anon_98a942599285"
        val result = BssidNormalizer.normalizeBssid(anon)
        assertEquals(anon, result)
    }

    @Test
    fun testGetDisplayLabel() {
        val label = BssidNormalizer.getDisplayLabel("98:a9:42:59:92:85")
        assertEquals("SLT-4G_599285", label)

        val anonLabel = BssidNormalizer.getDisplayLabel("anon_1234567890ab", "FriendlyName")
        assertEquals("FriendlyName", anonLabel)
    }
}
