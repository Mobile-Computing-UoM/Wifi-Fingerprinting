package com.example.wififingerprint.experiment

import android.content.Context
import java.io.File
import java.io.FileWriter

/**
 * Manages persistence, parsing, and export of experimental positioning validation data.
 */
class ExperimentCsvManager(private val context: Context) {

    companion object {
        const val EXPERIMENT_CSV_FILE_NAME = "experiment_results.csv"
        const val CSV_HEADER = "timestamp_ms,iso_time,separation_m,trial_idx,total_trials,locate_n,actual_spot,actual_x,actual_y,knn_pred_spot,knn_pred_x,knn_pred_y,knn_error_m,knn_correct,fuzzy_pred_spot,fuzzy_pred_x,fuzzy_pred_y,fuzzy_error_m,fuzzy_correct,raw_signals,pattern_pred_spot,pattern_pred_x,pattern_pred_y,pattern_error_m,pattern_correct,mamdani_pred_spot,mamdani_pred_x,mamdani_pred_y,mamdani_error_m,mamdani_correct"

        /**
         * Parses a CSV line safely respecting quotation marks around fields.
         */
        fun parseCsvLine(line: String): List<String> {
            val result = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            for (ch in line) {
                when {
                    ch == '\"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> {
                        result.add(sb.toString().trim())
                        sb.setLength(0)
                    }
                    else -> sb.append(ch)
                }
            }
            result.add(sb.toString().trim())
            return result
        }

        fun parseLine(line: String): ExperimentIterationResult? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("timestamp_ms")) return null
            val parts = parseCsvLine(trimmed)
            if (parts.size < 19) return null

            val ts = parts[0].toLongOrNull() ?: return null
            val iso = parts[1]
            val sep = parts[2].toDoubleOrNull() ?: 2.0
            val trialIdx = parts[3].toIntOrNull() ?: 1
            val totalTrials = parts[4].toIntOrNull() ?: 1
            val locateN = parts[5].toIntOrNull() ?: 1
            val actualSpot = parts[6]
            val actualX = parts[7].toDoubleOrNull() ?: 0.0
            val actualY = parts[8].toDoubleOrNull() ?: 0.0
            val knnSpot = parts[9]
            val knnX = parts[10].toDoubleOrNull() ?: 0.0
            val knnY = parts[11].toDoubleOrNull() ?: 0.0
            val knnErr = parts[12].toDoubleOrNull() ?: 0.0
            val knnCorr = parts[13] == "1" || parts[13].equals("true", ignoreCase = true)
            val fuzzySpot = parts[14]
            val fuzzyX = parts[15].toDoubleOrNull() ?: 0.0
            val fuzzyY = parts[16].toDoubleOrNull() ?: 0.0
            val fuzzyErr = parts[17].toDoubleOrNull() ?: 0.0
            val fuzzyCorr = parts[18] == "1" || parts[18].equals("true", ignoreCase = true)
            val signals = if (parts.size > 19) parts[19] else ""

            // Extended Pattern & Mamdani FIS columns (indices 20..29, backward-compatible)
            val patSpot = if (parts.size > 20) parts[20] else ""
            val patX = if (parts.size > 21) parts[21].toDoubleOrNull() ?: 0.0 else 0.0
            val patY = if (parts.size > 22) parts[22].toDoubleOrNull() ?: 0.0 else 0.0
            val patErr = if (parts.size > 23) parts[23].toDoubleOrNull() ?: 0.0 else 0.0
            val patCorr = if (parts.size > 24) (parts[24] == "1" || parts[24].equals("true", ignoreCase = true)) else false

            val mamSpot = if (parts.size > 25) parts[25] else ""
            val mamX = if (parts.size > 26) parts[26].toDoubleOrNull() ?: 0.0 else 0.0
            val mamY = if (parts.size > 27) parts[27].toDoubleOrNull() ?: 0.0 else 0.0
            val mamErr = if (parts.size > 28) parts[28].toDoubleOrNull() ?: 0.0 else 0.0
            val mamCorr = if (parts.size > 29) (parts[29] == "1" || parts[29].equals("true", ignoreCase = true)) else false

            return ExperimentIterationResult(
                timestampMs = ts,
                isoTime = iso,
                separationMeters = sep,
                trialIndex = trialIdx,
                totalTrials = totalTrials,
                locateScans = locateN,
                actualSpot = actualSpot,
                actualX = actualX,
                actualY = actualY,
                knnPredSpot = knnSpot,
                knnPredX = knnX,
                knnPredY = knnY,
                knnErrorMeters = knnErr,
                knnCorrect = knnCorr,
                fuzzyPredSpot = fuzzySpot,
                fuzzyPredX = fuzzyX,
                fuzzyPredY = fuzzyY,
                fuzzyErrorMeters = fuzzyErr,
                fuzzyCorrect = fuzzyCorr,
                rawSignals = signals,
                rawCsvLine = trimmed,
                patternPredSpot = patSpot,
                patternPredX = patX,
                patternPredY = patY,
                patternErrorMeters = patErr,
                patternCorrect = patCorr,
                mamdaniPredSpot = mamSpot,
                mamdaniPredX = mamX,
                mamdaniPredY = mamY,
                mamdaniErrorMeters = mamErr,
                mamdaniCorrect = mamCorr
            )
        }
    }

    fun getFile(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, EXPERIMENT_CSV_FILE_NAME)
    }

    /**
     * Appends an iteration result to the CSV file, adding the header if newly created.
     */
    @Synchronized
    fun appendResult(result: ExperimentIterationResult): Boolean {
        return try {
            val file = getFile()
            val isNew = !file.exists() || file.length() == 0L
            FileWriter(file, true).use { writer ->
                if (isNew) {
                    writer.append(CSV_HEADER).append("\n")
                }
                writer.append(result.rawCsvLine).append("\n")
                writer.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads all recorded iteration results from experiment_results.csv.
     */
    fun readAllResults(): List<ExperimentIterationResult> {
        val file = getFile()
        if (!file.exists()) return emptyList()
        val list = mutableListOf<ExperimentIterationResult>()
        try {
            file.forEachLine { line ->
                parseLine(line)?.let { list.add(it) }
            }
        } catch (_: Exception) {
            // Return what could be read
        }
        return list
    }

    /**
     * Deletes the experiment results file.
     */
    fun clearResults(): Boolean {
        val file = getFile()
        return if (file.exists()) file.delete() else true
    }

    /**
     * Deletes a single specific record from the CSV.
     */
    @Synchronized
    fun deleteRecord(record: ExperimentIterationResult): Boolean {
        val file = getFile()
        if (!file.exists()) return true
        return try {
            val lines = file.readLines()
            val remaining = lines.filter { line ->
                line.startsWith("timestamp_ms") || line.trim() != record.rawCsvLine.trim()
            }
            file.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
            true
        } catch (_: Exception) {
            false
        }
    }
}

