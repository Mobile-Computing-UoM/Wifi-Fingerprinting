package com.example.wififingerprint.data

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Encapsulates device identity and hardware/OS attributes for cross-device telemetry
 * and export metadata.
 */
data class DeviceInfoRecord(
    val deviceId: String,
    val model: String,
    val sdk: Int
)

object DeviceInfo {

    private const val PREFS_NAME = "device_meta_prefs"
    private const val KEY_DEVICE_ID = "stable_device_id"

    /**
     * Retrieves or generates a persistent random UUID for this device installation.
     */
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    /**
     * Human-readable device brand and model (e.g. "OPPO CPH2273" or "samsung SM-A202F").
     */
    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
    }

    fun getAndroidSdk(): Int = Build.VERSION.SDK_INT

    fun getRecord(context: Context): DeviceInfoRecord {
        return DeviceInfoRecord(
            deviceId = getDeviceId(context),
            model = getDeviceModel(),
            sdk = getAndroidSdk()
        )
    }
}
