package com.example.wififingerprint

import com.example.wififingerprint.strategy.TemporalScanBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalScanBufferTest {

    @Test
    fun testMedianFiltering_rejectsSingleScanDeepFade() {
        val buffer = TemporalScanBuffer(windowSize = 3, useMedian = true)

        // Normal scan 1
        buffer.addScan(mapOf("ap1" to -50, "ap2" to -70))
        // Scan 2 with deep fade on ap1 (-75 instead of ~ -50)
        buffer.addScan(mapOf("ap1" to -75, "ap2" to -71))
        // Normal scan 3
        val filtered = buffer.addScan(mapOf("ap1" to -52, "ap2" to -69))

        // Median of [-50, -75, -52] -> sorted is [-75, -52, -50] -> median is -52
        assertEquals(-52, filtered["ap1"])
        // Median of [-70, -71, -69] -> -70
        assertEquals(-70, filtered["ap2"])
    }

    @Test
    fun testWindowSliding_keepsWindowSizeConstant() {
        val buffer = TemporalScanBuffer(windowSize = 2, useMedian = false)

        buffer.addScan(mapOf("ap1" to -40))
        buffer.addScan(mapOf("ap1" to -50))
        assertEquals(2, buffer.currentCount)

        // Adding 3rd scan drops the 1st (-40)
        val filtered = buffer.addScan(mapOf("ap1" to -60))
        assertEquals(2, buffer.currentCount)
        // Average of [-50, -60] -> -55
        assertEquals(-55, filtered["ap1"])
    }

    @Test
    fun testMissingAp_retainedAcrossWindow() {
        val buffer = TemporalScanBuffer(windowSize = 3, useMedian = true)

        buffer.addScan(mapOf("ap1" to -60, "ap2" to -80))
        // ap2 is dropped by driver in scan 2
        buffer.addScan(mapOf("ap1" to -62))
        // ap2 seen again in scan 3
        val filtered = buffer.addScan(mapOf("ap1" to -61, "ap2" to -82))

        // ap2 should still be preserved in the filtered result
        assertTrue(filtered.containsKey("ap2"))
        assertEquals(-81, filtered["ap2"])
    }
}

