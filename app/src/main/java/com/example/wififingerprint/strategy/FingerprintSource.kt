package com.example.wififingerprint.strategy

/**
 * Data source selector for localization:
 * - LOCAL: Radio map surveyed on this device (wifi_fingerprints.csv)
 * - IMPORTED: Read-only reference map imported from another device (references/ref_*.csv)
 */
enum class FingerprintSource(val key: String, val title: String) {
    LOCAL("LOCAL", "Local Survey Map"),
    IMPORTED("IMPORTED", "Imported Reference Map");

    companion object {
        fun fromKey(key: String?): FingerprintSource {
            return entries.firstOrNull { it.key == key } ?: LOCAL
        }
    }
}
