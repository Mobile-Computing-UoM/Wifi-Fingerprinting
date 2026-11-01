package com.example.wififingerprint.data

import android.content.Context
import com.example.wififingerprint.strategy.RawSurveySpot
import java.io.File
import java.io.FileWriter

data class ReferenceSummary(
    val deviceId: String,
    val model: String,
    val sdk: Int,
    val exportedAt: Long,
    val spotCount: Int,
    val file: File
)

class ReferenceRepository(private val context: Context) {

    private fun getStorageDir(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val refDir = File(baseDir, "references")
        if (!refDir.exists()) {
            refDir.mkdirs()
        }
        return refDir
    }

    /**
     * Saves raw imported content into a device-keyed file (e.g. ref_a1b2c3d4.csv).
     */
    fun saveReference(metadata: FingerprintFileMetadata, content: String): File {
        val cleanId = metadata.deviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val targetFile = File(getStorageDir(), "ref_$cleanId.csv")
        FileWriter(targetFile, false).use { writer ->
            writer.write(content)
        }
        return targetFile
    }

    /**
     * Checks if at least one imported reference file exists.
     */
    fun hasReference(): Boolean {
        val files = getStorageDir().listFiles { f -> f.extension == "csv" }
        return !files.isNullOrEmpty()
    }

    /**
     * Lists summaries of all imported references.
     */
    fun listReferences(): List<ReferenceSummary> {
        val files = getStorageDir().listFiles { f -> f.extension == "csv" } ?: return emptyList()
        val results = mutableListOf<ReferenceSummary>()
        for (file in files.sortedByDescending { it.lastModified() }) {
            try {
                val content = file.readText()
                val (meta, spots) = FingerprintFile.parse(content)
                results.add(
                    ReferenceSummary(
                        deviceId = meta.deviceId,
                        model = meta.model,
                        sdk = meta.sdk,
                        exportedAt = meta.exportedAt,
                        spotCount = spots.size,
                        file = file
                    )
                )
            } catch (_: Exception) {
                // Ignore corrupt files
            }
        }
        return results
    }

    /**
     * Loads the most recently updated reference map.
     */
    fun loadLatestReference(): Pair<FingerprintFileMetadata, List<RawSurveySpot>>? {
        val files = getStorageDir().listFiles { f -> f.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?: return null

        for (file in files) {
            try {
                val content = file.readText()
                return FingerprintFile.parse(content)
            } catch (_: Exception) {
                // Continue to next file if this one fails
            }
        }
        return null
    }

    /**
     * Clears all imported reference maps.
     */
    fun clearReferences(): Boolean {
        var allDeleted = true
        val files = getStorageDir().listFiles { f -> f.extension == "csv" } ?: return true
        for (file in files) {
            if (!file.delete()) {
                allDeleted = false
            }
        }
        return allDeleted
    }
}
