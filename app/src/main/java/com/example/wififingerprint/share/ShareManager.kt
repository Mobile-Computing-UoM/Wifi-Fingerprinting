package com.example.wififingerprint.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.wififingerprint.data.DeviceInfo
import com.example.wififingerprint.data.FingerprintFile
import com.example.wififingerprint.data.FingerprintFileMetadata
import com.example.wififingerprint.strategy.RawSurveySpot
import java.io.File
import java.io.FileWriter

object ShareManager {

    /**
     * Serializes the current survey database with device metadata and generates an ACTION_SEND Intent.
     */
    fun createShareIntent(context: Context, spots: List<RawSurveySpot>): Intent {
        if (spots.isEmpty()) {
            throw IllegalArgumentException("Cannot share an empty survey database. Record at least one spot first.")
        }

        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        val exportFile = File(exportDir, "wifi_map_${DeviceInfo.getDeviceId(context)}_$timestamp.csv")

        val metadata = FingerprintFileMetadata(
            version = 1,
            deviceId = DeviceInfo.getDeviceId(context),
            model = DeviceInfo.getDeviceModel(),
            sdk = DeviceInfo.getAndroidSdk(),
            exportedAt = timestamp,
            calibA = 1.0,
            calibB = 0.0
        )

        val serializedContent = FingerprintFile.serialize(metadata, spots)
        FileWriter(exportFile, false).use { it.write(serializedContent) }

        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, exportFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Wi-Fi Radio Map Export (${spots.size} spots)")
            putExtra(Intent.EXTRA_TEXT, "Exported Wi-Fi radio map from ${metadata.model} with ${spots.size} spots.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
