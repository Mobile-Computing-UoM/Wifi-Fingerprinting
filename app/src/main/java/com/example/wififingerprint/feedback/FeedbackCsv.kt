package com.example.wififingerprint.feedback

import java.io.File
import java.io.FileWriter

/**
 * Data structure representing a single persisted ground-truth validation entry.
 * Backward-compatible with 10-column legacy records while supporting new cross-device telemetry.
 */
data class FeedbackHistoryRecord(
    val timestampMs: Long,
    val isoTime: String,
    val actualSpot: String,
    val knnPredicted: String,
    val knnCorrect: Boolean,
    val knnMargin: Double,
    val fuzzyPredicted: String,
    val fuzzyCorrect: Boolean,
    val fuzzyMargin: Double,
    val querySignals: String,
    val patternPredicted: String = "",
    val patternCorrect: Boolean = false,
    val patternMargin: Double = 0.0,
    val mapSource: String = "LOCAL",
    val refDeviceModel: String = "",
    val locateDeviceModel: String = "",
    val calibOffsetDb: Double? = null,
    val rawCsvLine: String = ""
)

object FeedbackCsv {

    const val HEADER = "timestamp_ms,iso_time,actual_spot,knn_predicted,knn_correct,knn_margin,fuzzy_predicted,fuzzy_correct,fuzzy_margin,query_signals,pattern_predicted,pattern_correct,pattern_margin,map_source,ref_device_model,locate_device_model,calib_offset_db"

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

    /**
     * Parses a single row. Supports both legacy 10-column and new 17-column rows.
     */
    fun parseLine(line: String): FeedbackHistoryRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("timestamp_ms")) return null

        val parts = parseCsvLine(trimmed)
        if (parts.size < 9) return null

        val timestampMs = parts[0].toLongOrNull() ?: 0L
        val isoTime = parts[1]
        val actualSpot = parts[2]
        val knnPredicted = parts[3]
        val knnCorrect = (parts[4] == "1" || parts[4].equals("true", ignoreCase = true))
        val knnMargin = parts[5].toDoubleOrNull() ?: 0.0
        val fuzzyPredicted = parts[6]
        val fuzzyCorrect = (parts[7] == "1" || parts[7].equals("true", ignoreCase = true))
        val fuzzyMargin = parts[8].toDoubleOrNull() ?: 0.0
        val querySignals = parts.getOrNull(9)?.trim('\"') ?: ""

        // Extended cross-device columns (indices 10..16)
        val patternPredicted = parts.getOrNull(10)?.trim() ?: ""
        val patternCorrect = if (parts.size > 11) {
            (parts[11] == "1" || parts[11].equals("true", ignoreCase = true))
        } else {
            patternPredicted.isNotBlank() && patternPredicted.equals(actualSpot, ignoreCase = true)
        }
        val patternMargin = parts.getOrNull(12)?.toDoubleOrNull() ?: 0.0
        val mapSource = parts.getOrNull(13)?.trim()?.ifEmpty { "LOCAL" } ?: "LOCAL"
        val refDeviceModel = parts.getOrNull(14)?.trim() ?: ""
        val locateDeviceModel = parts.getOrNull(15)?.trim() ?: ""
        val calibOffsetDb = parts.getOrNull(16)?.trim()?.toDoubleOrNull()

        return FeedbackHistoryRecord(
            timestampMs = timestampMs,
            isoTime = isoTime,
            actualSpot = actualSpot,
            knnPredicted = knnPredicted,
            knnCorrect = knnCorrect,
            knnMargin = knnMargin,
            fuzzyPredicted = fuzzyPredicted,
            fuzzyCorrect = fuzzyCorrect,
            fuzzyMargin = fuzzyMargin,
            querySignals = querySignals,
            patternPredicted = patternPredicted,
            patternCorrect = patternCorrect,
            patternMargin = patternMargin,
            mapSource = mapSource,
            refDeviceModel = refDeviceModel,
            locateDeviceModel = locateDeviceModel,
            calibOffsetDb = calibOffsetDb,
            rawCsvLine = trimmed
        )
    }

    /**
     * Serializes a FeedbackHistoryRecord into a CSV line conforming to the 17-column format.
     */
    fun serialize(record: FeedbackHistoryRecord): String {
        val knnCorrectInt = if (record.knnCorrect) 1 else 0
        val fuzzyCorrectInt = if (record.fuzzyCorrect) 1 else 0
        val patternCorrectInt = if (record.patternCorrect) 1 else 0
        val calibStr = record.calibOffsetDb?.let { "%.2f".format(java.util.Locale.US, it) } ?: ""

        return "${record.timestampMs},${record.isoTime},${record.actualSpot}," +
                "${record.knnPredicted},$knnCorrectInt,${record.knnMargin}," +
                "${record.fuzzyPredicted},$fuzzyCorrectInt,${record.fuzzyMargin}," +
                "\"${record.querySignals}\"," +
                "${record.patternPredicted},$patternCorrectInt,${record.patternMargin}," +
                "${record.mapSource},${record.refDeviceModel},${record.locateDeviceModel}," +
                calibStr
    }

    /**
     * Appends a record to the CSV file. If file does not exist, writes the header first.
     */
    fun append(file: File, record: FeedbackHistoryRecord) {
        val isNew = !file.exists() || file.length() == 0L
        FileWriter(file, true).use { writer ->
            if (isNew) {
                writer.append("$HEADER\n")
            }
            writer.append(serialize(record) + "\n")
        }
    }

    /**
     * Reads all feedback records from the CSV file.
     */
    fun loadAll(file: File): List<FeedbackHistoryRecord> {
        if (!file.exists()) return emptyList()
        val list = mutableListOf<FeedbackHistoryRecord>()
        file.forEachLine { line ->
            parseLine(line)?.let { list.add(it) }
        }
        return list.reversed() // Most recent first
    }

    /**
     * Deletes a record by exact raw line match.
     */
    fun delete(file: File, rawLine: String): Boolean {
        if (!file.exists()) return false
        val lines = file.readLines()
        val remaining = lines.filter { line ->
            line.startsWith("timestamp_ms") || line.trim() != rawLine.trim()
        }
        file.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
        return true
    }
}
