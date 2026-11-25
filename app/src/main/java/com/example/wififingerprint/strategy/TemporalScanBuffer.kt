package com.example.wififingerprint.strategy

import kotlin.math.roundToInt

/**
 * Temporal Sliding Window Preprocessor for Live Wi-Fi Scans.
 *
 * Buffers the last [windowSize] consecutive scans and aggregates RSSI measurements
 * per AP using either median (default, outlier-robust) or mean filtering.
 *
 * This dramatically reduces the impact of multipath deep-fades, body shadowing,
 * and random scan dropouts before feeding the signal vector into localization strategies.
 */
class TemporalScanBuffer(
    val windowSize: Int = 3,
    val useMedian: Boolean = true
) {
    private val window = ArrayDeque<Map<String, Int>>()

    /**
     * Adds a new instantaneous live scan map (BSSID -> RSSI) to the sliding window
     * and returns the preprocessed/filtered signal map.
     */
    @Synchronized
    fun addScan(scan: Map<String, Int>): Map<String, Int> {
        if (window.size >= windowSize) {
            window.removeFirst()
        }
        window.addLast(scan)
        return getFilteredScan()
    }

    /**
     * Computes the aggregated fingerprint across all scans currently in the sliding window.
     */
    @Synchronized
    fun getFilteredScan(): Map<String, Int> {
        if (window.isEmpty()) return emptyMap()

        val apReadings = mutableMapOf<String, MutableList<Int>>()
        for (scan in window) {
            for ((bssid, rssi) in scan) {
                apReadings.getOrPut(bssid) { mutableListOf() }.add(rssi)
            }
        }

        val filtered = mutableMapOf<String, Int>()
        for ((bssid, readings) in apReadings) {
            val aggregated = if (useMedian) {
                val sorted = readings.sorted()
                val mid = sorted.size / 2
                if (sorted.size % 2 == 1) {
                    sorted[mid]
                } else {
                    ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToInt()
                }
            } else {
                readings.average().roundToInt()
            }
            filtered[bssid] = aggregated
        }
        return filtered
    }

    /**
     * Clears all buffered scans (e.g. when beginning a new location survey or switching modes).
     */
    @Synchronized
    fun clear() {
        window.clear()
    }

    /**
     * Returns the current number of scans held in the buffer.
     */
    val currentCount: Int
        @Synchronized get() = window.size
}

