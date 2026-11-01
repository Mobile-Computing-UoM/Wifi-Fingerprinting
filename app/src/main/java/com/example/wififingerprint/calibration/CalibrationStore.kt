package com.example.wififingerprint.calibration

import android.content.Context

data class DeviceCalibration(
    val refDeviceId: String,
    val offsetDb: Double,
    val spotLabel: String,
    val sharedAps: Int,
    val stdDevDb: Double,
    val timestampMs: Long
)

object CalibrationStore {

    private const val PREFS_NAME = "device_calibration_store"

    fun saveCalibration(context: Context, calib: DeviceCalibration) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "calib_${calib.refDeviceId}_"
        prefs.edit()
            .putFloat("${prefix}offset", calib.offsetDb.toFloat())
            .putString("${prefix}spot", calib.spotLabel)
            .putInt("${prefix}aps", calib.sharedAps)
            .putFloat("${prefix}stddev", calib.stdDevDb.toFloat())
            .putLong("${prefix}time", calib.timestampMs)
            .apply()
    }

    fun getCalibration(context: Context, refDeviceId: String): DeviceCalibration? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "calib_${refDeviceId}_"
        if (!prefs.contains("${prefix}offset")) return null

        val offset = prefs.getFloat("${prefix}offset", 0f).toDouble()
        val spot = prefs.getString("${prefix}spot", "") ?: ""
        val aps = prefs.getInt("${prefix}aps", 0)
        val stdDev = prefs.getFloat("${prefix}stddev", 0f).toDouble()
        val time = prefs.getLong("${prefix}time", 0L)

        return DeviceCalibration(
            refDeviceId = refDeviceId,
            offsetDb = offset,
            spotLabel = spot,
            sharedAps = aps,
            stdDevDb = stdDev,
            timestampMs = time
        )
    }

    fun clearCalibration(context: Context, refDeviceId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "calib_${refDeviceId}_"
        prefs.edit()
            .remove("${prefix}offset")
            .remove("${prefix}spot")
            .remove("${prefix}aps")
            .remove("${prefix}stddev")
            .remove("${prefix}time")
            .apply()
    }
}
