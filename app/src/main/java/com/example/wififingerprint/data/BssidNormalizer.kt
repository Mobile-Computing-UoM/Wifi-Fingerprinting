package com.example.wififingerprint.data

import java.security.MessageDigest
import java.util.Locale

/**
 * Single source of truth for deterministic BSSID normalization and privacy hashing.
 *
 * Ensures consistent canonical IDs across different devices (Oppo, Samsung, LineageOS)
 * and across local vs imported radio maps.
 */
object BssidNormalizer {

    // Friendly reference names for known project APs (for UI/debug logging)
    val KNOWN_PROJECT_APS = mapOf(
        "98:a9:42:59:92:85" to "SLT-4G_599285",
        "98:a9:42:0b:16:4a" to "Dialog 4G 610",
        "a0:c4:c6:a6:fc:18" to "Project-AP-3"
    )

    /**
     * Normalizes any BSSID to canonical 'anon_<12-hex>' format.
     *
     * - If already in 'anon_...' format, returns it as-is (lowercased).
     * - If standard MAC address (e.g. "98:A9:42:59:92:85"), computes SHA-256 and takes first 6 bytes.
     * - Produces identical results across all Android devices, JVMs, and OS builds.
     */
    fun normalizeBssid(rawBssid: String): String {
        val norm = rawBssid.trim().lowercase(Locale.US)
        if (norm.startsWith("anon_")) {
            return norm
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(norm.toByteArray(Charsets.UTF_8))
            val hex = digest.take(6).joinToString("") { "%02x".format(it) }
            "anon_$hex"
        } catch (e: Exception) {
            "anon_${norm.hashCode().toUInt().toString(16)}"
        }
    }

    /**
     * Returns a human-friendly display label for an AP.
     * Uses project AP mappings if known, or provided SSID, or fallback.
     */
    fun getDisplayLabel(bssid: String, fallbackSsid: String? = null): String {
        val norm = bssid.trim().lowercase(Locale.US)
        KNOWN_PROJECT_APS[norm]?.let { return it }

        // Check if raw BSSID hashes to this normalized ID
        for ((knownMac, name) in KNOWN_PROJECT_APS) {
            if (normalizeBssid(knownMac) == norm) {
                return name
            }
        }

        if (!fallbackSsid.isNullOrBlank() && fallbackSsid != "Unknown" && fallbackSsid != "Anon-AP") {
            return fallbackSsid
        }
        return norm
    }
}
