package com.example.wififingerprint.share

import android.content.Context
import android.net.Uri
import com.example.wififingerprint.data.FingerprintFile
import com.example.wififingerprint.data.FingerprintFileMetadata
import com.example.wififingerprint.data.ReferenceRepository
import com.example.wififingerprint.strategy.RawSurveySpot

data class ImportResult(
    val metadata: FingerprintFileMetadata,
    val spots: List<RawSurveySpot>,
    val savedPath: String
)

class ImportManager(private val context: Context) {

    private val repository = ReferenceRepository(context)

    /**
     * Reads, validates and stores an imported fingerprint file from a content Uri.
     */
    fun importFromUri(uri: Uri): ImportResult {
        val content = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("Could not open input stream for selected file")

        if (content.isBlank()) {
            throw IllegalArgumentException("The selected file is empty.")
        }

        // Validate and parse format
        val (metadata, spots) = FingerprintFile.parse(content)
        if (spots.isEmpty()) {
            throw IllegalArgumentException("No valid spot survey data could be parsed from the file.")
        }

        // Persist into read-only reference repository
        val savedFile = repository.saveReference(metadata, content)

        return ImportResult(
            metadata = metadata,
            spots = spots,
            savedPath = savedFile.absolutePath
        )
    }
}
