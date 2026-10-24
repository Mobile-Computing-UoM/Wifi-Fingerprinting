package com.example.wififingerprint

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.graphics.Color
import android.graphics.Typeface
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.wififingerprint.strategy.FuzzyMatrixStrategy
import com.example.wififingerprint.strategy.KnnStrategy
import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.LocalizationResult
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import com.example.wififingerprint.strategy.StrategyMode
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// Legacy data class kept for backwards compatibility if needed
data class Fingerprint(
    val spotLabel: String,
    val readings: Map<String, Int>
)

class MainActivity : AppCompatActivity() {
    private lateinit var editSpotName: EditText
    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnSurvey: Button
    private lateinit var btnLocate: Button
    private lateinit var btnClearData: Button
    private lateinit var btnSettings: Button
    private lateinit var btnViewMatrix: Button
    private lateinit var tvSettingsSummary: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvProgressText: TextView

    private lateinit var wifiManager: WifiManager
    private val surveyDatabase = mutableListOf<RawSurveySpot>()
    private val CSV_FILE_NAME = "wifi_fingerprints.csv"
    private val FEEDBACK_CSV_NAME = "localization_feedback.csv"

    // Strategy Pattern instances
    private var selectedStrategyMode: StrategyMode = StrategyMode.BOTH
    private val knnStrategy = KnnStrategy(1)
    private val fuzzyMatrixStrategy = FuzzyMatrixStrategy()

    // Dynamic WakeLock to keep screen on ONLY while surveying
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireScreenWakeLock() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            findViewById<View>(android.R.id.content)?.keepScreenOn = true

            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "WifiFingerprint:SurveyWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L) // 1-hour safety timeout
            }
        } catch (e: Exception) {
            // Ignore if device power policy restricts wake lock
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            findViewById<View>(android.R.id.content)?.keepScreenOn = false

            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // Configurable parameters (persisted in SharedPreferences)
    private var surveySampleCount = 20    // Scans per survey spot
    private var locateSampleCount = 10    // Scans for locate
    private var knnK = 1                 // k for k-NN matching
    private var activeTargetSamples = 20 // Current active target

    private var currentSampleCount = 0
    private var isSurveying = true // true = Survey mode, false = Locate mode
    private var isScanningActive = false // Only true when user explicitly pressed a button

    private val sampleBuffer = mutableMapOf<String, MutableList<Int>>()
    private val bssidToSsidMap = mutableMapOf<String, String>()
    private val scanHandler = Handler(Looper.getMainLooper())

    private fun rebuildStrategyModels() {
        knnStrategy.k = knnK
        knnStrategy.buildModel(surveyDatabase)
        fuzzyMatrixStrategy.buildModel(surveyDatabase)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        surveySampleCount = prefs.getInt("survey_count", 20)
        locateSampleCount = prefs.getInt("locate_count", 10)
        knnK = prefs.getInt("knn_k", 1)
        selectedStrategyMode = StrategyMode.fromKey(prefs.getString("strategy_mode", StrategyMode.BOTH.key))
        knnStrategy.k = knnK
        updateSettingsSummary()
    }

    private fun saveSettings(survey: Int, locate: Int, knn: Int, mode: StrategyMode) {
        surveySampleCount = survey
        locateSampleCount = locate
        knnK = knn
        selectedStrategyMode = mode
        knnStrategy.k = knnK

        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("survey_count", survey)
            putInt("locate_count", locate)
            putInt("knn_k", knn)
            putString("strategy_mode", mode.key)
            apply()
        }
        updateSettingsSummary()
        if (!isScanningActive) {
            updateProgressDisplay(0, surveySampleCount, "Survey Progress")
        }
        log("\n⚙️ Settings saved: Survey=$survey | Locate=$locate | k-NN=$knn | Strategy=${mode.title}\n")
    }

    private fun updateProgressDisplay(current: Int, total: Int, label: String? = null) {
        if (label != null) {
            tvProgressLabel.text = label
        }
        val percent = if (total > 0) (current * 100) / total else 0
        progressBar.progress = percent
        tvProgressText.text = "$current/$total ($percent% completed)"
    }

    private fun updateSettingsSummary() {
        val stratShort = when (selectedStrategyMode) {
            StrategyMode.KNN -> "k-NN"
            StrategyMode.FUZZY_MATRIX -> "Fuzzy Matrix"
            StrategyMode.BOTH -> "Both (Side-by-Side)"
        }
        tvSettingsSummary.text = "Params: Survey=$surveySampleCount | Locate=$locateSampleCount | k-NN=$knnK | Mode: $stratShort"

        // Dynamically update locate button label
        btnLocate.text = when (selectedStrategyMode) {
            StrategyMode.KNN -> "2. Where Am I? (k-NN)"
            StrategyMode.FUZZY_MATRIX -> "2. Where Am I? (Fuzzy Matrix)"
            StrategyMode.BOTH -> "2. Where Am I? (Compare Both)"
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val editSurvey = dialogView.findViewById<EditText>(R.id.editSurveyCount)
        val editLocate = dialogView.findViewById<EditText>(R.id.editLocateCount)
        val editKnn = dialogView.findViewById<EditText>(R.id.editKnnK)

        val rbBoth = dialogView.findViewById<RadioButton>(R.id.rbStrategyBoth)
        val rbKnn = dialogView.findViewById<RadioButton>(R.id.rbStrategyKnn)
        val rbFuzzy = dialogView.findViewById<RadioButton>(R.id.rbStrategyFuzzy)

        editSurvey.setText(surveySampleCount.toString())
        editLocate.setText(locateSampleCount.toString())
        editKnn.setText(knnK.toString())

        when (selectedStrategyMode) {
            StrategyMode.BOTH -> rbBoth.isChecked = true
            StrategyMode.KNN -> rbKnn.isChecked = true
            StrategyMode.FUZZY_MATRIX -> rbFuzzy.isChecked = true
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Configure Parameters & Strategy")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sCount = editSurvey.text.toString().toIntOrNull() ?: surveySampleCount
                val lCount = editLocate.text.toString().toIntOrNull() ?: locateSampleCount
                val kVal = editKnn.text.toString().toIntOrNull() ?: knnK

                val chosenMode = when {
                    rbKnn.isChecked -> StrategyMode.KNN
                    rbFuzzy.isChecked -> StrategyMode.FUZZY_MATRIX
                    else -> StrategyMode.BOTH
                }

                if (sCount <= 0 || lCount <= 0 || kVal <= 0) {
                    Toast.makeText(this, "Parameters must be positive integers!", Toast.LENGTH_SHORT).show()
                } else {
                    saveSettings(sCount, lCount, kVal, chosenMode)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Structure holding all raw measurements and calculated average for each AP
    data class ApMeasurement(
        val bssid: String,
        val ssid: String,
        val rawReadings: List<Int>,
        val avgRssi: Int
    )

    private fun processAndSaveAveragedFingerprint() {
        val processedMeasurements = mutableListOf<ApMeasurement>()
        val averagedFingerprint = mutableMapOf<String, Int>()

        for ((bssid, rawReadings) in sampleBuffer) {
            val ssid = bssidToSsidMap[bssid] ?: "Unknown"
            val avg = rawReadings.average().roundToInt()

            averagedFingerprint[bssid] = avg
            processedMeasurements.add(ApMeasurement(bssid, ssid, rawReadings, avg))
        }

        if (isSurveying) {
            val spot = editSpotName.text.toString().trim()
            updateProgressDisplay(activeTargetSamples, activeTargetSamples, "Survey Completed: '$spot'")
            log("\n--- Survey Results for '$spot' ($activeTargetSamples iterations) ---\n")
            for (m in processedMeasurements) {
                log("• ${m.ssid} (${m.bssid}): Avg ${m.avgRssi} dBm (${m.rawReadings.size} samples)\n")
            }

            // Convert to RawSurveySpot representation and save to database
            val rawMeasurements = processedMeasurements.map {
                RawApMeasurement(it.bssid, it.ssid, it.rawReadings, it.avgRssi)
            }
            val spotRecord = RawSurveySpot(spot, rawMeasurements, activeTargetSamples)
            surveyDatabase.add(spotRecord)

            // Rebuild Strategy Models dynamically with the newly added spot
            rebuildStrategyModels()

            // Save to CSV file with all raw samples
            saveFingerprintToCsv(spot, processedMeasurements)

            log("💾 Saved '$spot' with ${averagedFingerprint.size} APs to database & CSV.\n")
            log("Total spots in memory: ${surveyDatabase.size}\n")
        } else {
            updateProgressDisplay(activeTargetSamples, activeTargetSamples, "Locate Completed")
            log("\n--- Live Scan ($activeTargetSamples iterations) ---\n")
            for (m in processedMeasurements) {
                log("${m.ssid} (${m.bssid})\n")
                log("  Readings (${m.rawReadings.size}): ${m.rawReadings} -> Avg: ${m.avgRssi} dBm\n")
            }

            // Execute localization using the Strategy Pattern
            val liveQuery = LiveQueryScan(averagedFingerprint, bssidToSsidMap)
            executeLocalization(liveQuery)
        }

        // FINISHED: Unlock scanning state and re-enable buttons
        isScanningActive = false
        btnSurvey.isEnabled = true
        btnLocate.isEnabled = true
        btnClearData.isEnabled = true
        btnSettings.isEnabled = true
        btnViewMatrix.isEnabled = true
        releaseScreenWakeLock()
    }

    /**
     * Executes localization using the configured Strategy Pattern mode.
     * Evaluates both algorithms to enable real-world side-by-side comparison
     * and prompts the user for ground-truth validation feedback.
     */
    private fun executeLocalization(query: LiveQueryScan) {
        if (surveyDatabase.isEmpty()) {
            log("\n⚠️ No survey spots in database to compare against!\n")
            return
        }

        rebuildStrategyModels()

        // Evaluate both strategies on the exact same live query data
        val resKnn = knnStrategy.infer(query)
        val resFuzzy = fuzzyMatrixStrategy.infer(query)

        when (selectedStrategyMode) {
            StrategyMode.KNN -> {
                log(resKnn.logOutput)
            }

            StrategyMode.FUZZY_MATRIX -> {
                log(resFuzzy.logOutput)
            }

            StrategyMode.BOTH -> {
                log(resKnn.logOutput)
                log(resFuzzy.logOutput)

                // High-visibility side-by-side comparison summary
                val knnWinner = resKnn.predictedSpot ?: "None"
                val fuzzyWinner = resFuzzy.predictedSpot ?: "None"
                val agreement = if (knnWinner == fuzzyWinner) {
                    "🎯 COMPLETE AGREEMENT: Both algorithms predicted '$knnWinner'"
                } else {
                    "⚠️ DIVERGENCE: k-NN predicted '$knnWinner' vs. Fuzzy Matrix predicted '$fuzzyWinner'"
                }

                val summary = StringBuilder()
                summary.append("\n========================================\n")
                summary.append("⚖️ DUAL STRATEGY COMPARISON (Same Scan Data)\n")
                summary.append("========================================\n")
                val knnGap = if (resKnn.confidenceMargin != null) " (Gap: ${"%.2f".format(resKnn.confidenceMargin)} dBm)" else ""
                val fuzzyMargin = if (resFuzzy.confidenceMargin != null) " (Winning Margin: +${"%.4f".format(resFuzzy.confidenceMargin)})" else ""

                summary.append("• Strategy 1 (k-NN):         $knnWinner$knnGap\n")
                summary.append("• Strategy 2 (Fuzzy Matrix): $fuzzyWinner$fuzzyMargin\n")
                summary.append("\n$agreement\n")
                summary.append("========================================\n\n")

                log(summary.toString())
            }
        }

        // Prompt user for ground-truth accuracy feedback and persist outcome
        showFeedbackDialog(query, resKnn, resFuzzy)
    }

    /**
     * Interactive validation dialog asking user whether prediction is correct
     * and selecting the actual physical location to persist for empirical accuracy analysis.
     */
    private fun showFeedbackDialog(
        query: LiveQueryScan,
        resKnn: LocalizationResult,
        resFuzzy: LocalizationResult
    ) {
        val spotNames = surveyDatabase.map { it.spotLabel }.distinct().sorted()
        if (spotNames.isEmpty()) return

        val knnWinner = resKnn.predictedSpot ?: "None"
        val fuzzyWinner = resFuzzy.predictedSpot ?: "None"

        val dialogView = layoutInflater.inflate(R.layout.dialog_feedback, null, false)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvFeedbackSummary)
        val spinnerActualSpot = dialogView.findViewById<Spinner>(R.id.spinnerActualSpot)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spotNames)
        spinnerActualSpot.adapter = adapter

        // Pre-select predicted spot
        val defaultSelection = if (spotNames.contains(fuzzyWinner)) fuzzyWinner else if (spotNames.contains(knnWinner)) knnWinner else spotNames.first()
        val defaultIdx = spotNames.indexOf(defaultSelection)
        if (defaultIdx >= 0) {
            spinnerActualSpot.setSelection(defaultIdx)
        }

        val knnGapStr = if (resKnn.confidenceMargin != null) " (Gap: ${"%.2f".format(resKnn.confidenceMargin)} dBm)" else ""
        val fuzzyMarginStr = if (resFuzzy.confidenceMargin != null) " (Margin: +${"%.4f".format(resFuzzy.confidenceMargin)})" else ""

        tvSummary.text = "Inference Predictions:\n" +
                "• Strategy 1 (k-NN): $knnWinner$knnGapStr\n" +
                "• Strategy 2 (Fuzzy): $fuzzyWinner$fuzzyMarginStr\n\n" +
                "Please select your ACTUAL current location to evaluate and persist accuracy:"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎯 Validate Prediction Accuracy")
            .setView(dialogView)
            .setPositiveButton("✅ Confirm & Save") { _, _ ->
                val actualSpot = spinnerActualSpot.selectedItem?.toString() ?: defaultSelection
                val isKnnCorrect = (actualSpot == knnWinner)
                val isFuzzyCorrect = (actualSpot == fuzzyWinner)

                saveEvaluationRecord(actualSpot, resKnn, resFuzzy, query)

                val feedbackMsg = StringBuilder()
                feedbackMsg.append("\n========================================\n")
                feedbackMsg.append("📝 USER VALIDATION PERSISTED\n")
                feedbackMsg.append("========================================\n")
                feedbackMsg.append("• Actual Ground Truth:  $actualSpot\n")
                feedbackMsg.append("• Strategy 1 (k-NN):    $knnWinner -> ${if (isKnnCorrect) "✅ CORRECT" else "❌ WRONG"}\n")
                feedbackMsg.append("• Strategy 2 (Fuzzy):   $fuzzyWinner -> ${if (isFuzzyCorrect) "✅ CORRECT" else "❌ WRONG"}\n")
                feedbackMsg.append("💾 Saved to $FEEDBACK_CSV_NAME for further analysis\n")
                feedbackMsg.append("========================================\n\n")
                log(feedbackMsg.toString())

                Toast.makeText(this, "Validation saved for '$actualSpot'!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    /**
     * Appends validation result to localization_feedback.csv.
     */
    private fun saveEvaluationRecord(
        actualSpot: String,
        resKnn: LocalizationResult,
        resFuzzy: LocalizationResult,
        query: LiveQueryScan
    ) {
        try {
            val file = File(getExternalFilesDir(null), FEEDBACK_CSV_NAME)
            val isNewFile = !file.exists()
            val writer = FileWriter(file, true) // append mode
            val timestamp = System.currentTimeMillis()
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val isoTime = isoFormat.format(Date(timestamp))

            if (isNewFile) {
                writer.append("timestamp_ms,iso_time,actual_spot,knn_predicted,knn_correct,knn_margin,fuzzy_predicted,fuzzy_correct,fuzzy_margin,query_signals\n")
            }

            val knnPred = resKnn.predictedSpot ?: "None"
            val knnCorrect = if (knnPred == actualSpot) 1 else 0
            val knnMargin = resKnn.confidenceMargin ?: 0.0

            val fuzzyPred = resFuzzy.predictedSpot ?: "None"
            val fuzzyCorrect = if (fuzzyPred == actualSpot) 1 else 0
            val fuzzyMargin = resFuzzy.confidenceMargin ?: 0.0

            val queryStr = query.readings.entries.joinToString(";") { "${it.key}:${it.value}" }

            writer.append("$timestamp,$isoTime,$actualSpot,$knnPred,$knnCorrect,$knnMargin,$fuzzyPred,$fuzzyCorrect,$fuzzyMargin,\"$queryStr\"\n")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            log("❌ Error writing feedback CSV: ${e.message}\n")
        }
    }

    /**
     * Displays a full scrollable dialog showing the complete 3D Fuzzy Matrix model.
     */
    private fun showFuzzyMatrixDialog() {
        if (!fuzzyMatrixStrategy.isModelLoaded()) {
            Toast.makeText(this, "Fuzzy Matrix is empty! Survey spots or load data first.", Toast.LENGTH_SHORT).show()
            return
        }

        val matrixText = fuzzyMatrixStrategy.getFormattedMatrix()

        val scrollView = ScrollView(this).apply {
            setPadding(32, 24, 32, 24)
        }
        val tv = TextView(this).apply {
            text = matrixText
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        scrollView.addView(tv)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📊 3D Fuzzy Matrix View")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Print to Log") { _, _ ->
                log("\n" + matrixText + "\n")
            }
            .show()
    }

    // Save fingerprint entry into wifi_fingerprints.csv including ALL raw measurements
    private fun saveFingerprintToCsv(spot: String, measurements: List<ApMeasurement>) {
        try {
            val file = File(getExternalFilesDir(null), CSV_FILE_NAME)
            val isNewFile = !file.exists()
            val writer = FileWriter(file, true) // append mode
            val timestamp = System.currentTimeMillis()

            if (isNewFile) {
                writer.append("timestamp,spot_label,bssid,ssid,sample_count,samples,avg_rssi\n")
            }

            for (m in measurements) {
                val cleanSsid = m.ssid.replace(",", " ")
                val rawStr = "\"[" + m.rawReadings.joinToString(";") + "]\""
                writer.append("$timestamp,$spot,${m.bssid},$cleanSsid,${m.rawReadings.size},$rawStr,${m.avgRssi}\n")
            }

            writer.flush()
            writer.close()
        } catch (e: Exception) {
            log("❌ Error writing CSV: ${e.message}\n")
        }
    }

    // Load previously saved survey data from wifi_fingerprints.csv on startup
    // Supports both old 5-column and new 7-column formats, reconstructing full raw readings
    private fun loadFingerprintsFromCsv() {
        try {
            val file = File(getExternalFilesDir(null), CSV_FILE_NAME)
            if (!file.exists()) return

            // Map: SpotLabel -> (BSSID -> RawApMeasurement)
            val spotMeasurements = mutableMapOf<String, MutableMap<String, RawApMeasurement>>()
            val spotMaxScans = mutableMapOf<String, Int>()

            file.forEachLine { line ->
                if (!line.startsWith("timestamp") && line.isNotBlank()) {
                    if (line.contains("\"[")) {
                        // 7-column format with raw sample array
                        val parts = line.split("\"")
                        val meta = parts[0].split(",")
                        val spot = meta.getOrNull(1)?.trim() ?: ""
                        val bssid = meta.getOrNull(2)?.trim() ?: ""
                        val ssid = meta.getOrNull(3)?.trim() ?: "Unknown"
                        val count = meta.getOrNull(4)?.trim()?.toIntOrNull() ?: 1

                        val rawList = if (parts.size > 1) {
                            parts[1].trim(' ', '[', ']')
                                .split(";")
                                .mapNotNull { it.toIntOrNull() }
                        } else emptyList()

                        val avg = parts.getOrNull(2)?.trim(',', ' ')?.toIntOrNull()
                            ?: (if (rawList.isNotEmpty()) rawList.average().roundToInt() else -100)

                        if (spot.isNotEmpty() && bssid.isNotEmpty()) {
                            val currentMax = spotMaxScans[spot] ?: 0
                            spotMaxScans[spot] = maxOf(currentMax, count, rawList.size)
                            spotMeasurements.getOrPut(spot) { mutableMapOf() }[bssid] =
                                RawApMeasurement(bssid, ssid, rawList.ifEmpty { listOf(avg) }, avg)
                        }
                    } else {
                        // Legacy 5-column format: timestamp,spot_label,bssid,ssid,avg_rssi
                        val parts = line.split(",")
                        if (parts.size >= 5) {
                            val spot = parts[1].trim()
                            val bssid = parts[2].trim()
                            val ssid = parts[3].trim()
                            val rssi = parts.last().trim().toIntOrNull() ?: -100

                            val currentMax = spotMaxScans[spot] ?: 0
                            spotMaxScans[spot] = maxOf(currentMax, 1)
                            spotMeasurements.getOrPut(spot) { mutableMapOf() }[bssid] =
                                RawApMeasurement(bssid, ssid, listOf(rssi), rssi)
                        }
                    }
                }
            }

            surveyDatabase.clear()
            for ((spot, bssidMap) in spotMeasurements) {
                val totalScans = spotMaxScans[spot] ?: 1
                surveyDatabase.add(RawSurveySpot(spot, bssidMap.values.toList(), totalScans))
            }

            rebuildStrategyModels()
            log("📂 Loaded ${surveyDatabase.size} saved spot(s) from ${file.name}\n")
            if (fuzzyMatrixStrategy.isModelLoaded()) {
                log("📊 Fuzzy 3D Matrix built (${surveyDatabase.size} spots × ${fuzzyMatrixStrategy.getAllKnownAps().size} APs). Tap 'View Matrix' to inspect.\n")
            }
        } catch (e: Exception) {
            log("❌ Error reading CSV: ${e.message}\n")
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isScanningActive) return

            val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (!updated) {
                scanHandler.postDelayed({
                    if (isScanningActive) triggerWifiScan()
                }, 1500)
                return
            }

            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                isScanningActive = false
                btnSurvey.isEnabled = true
                btnLocate.isEnabled = true
                btnClearData.isEnabled = true
                btnSettings.isEnabled = true
                btnViewMatrix.isEnabled = true
                releaseScreenWakeLock()
                return
            }

            val scanResults = wifiManager.scanResults
            if (scanResults.isEmpty()) {
                scanHandler.postDelayed({
                    if (isScanningActive) triggerWifiScan()
                }, 1200)
                return
            }

            currentSampleCount++
            val spot = if (isSurveying) editSpotName.text.toString().trim() else "Locate Position"
            updateProgressDisplay(currentSampleCount, activeTargetSamples, if (isSurveying) "Surveying '$spot'..." else "Locating...")

            for (result in scanResults) {
                val bssid = result.BSSID ?: continue
                val ssid = if (result.SSID.isNullOrEmpty()) "Hidden" else result.SSID
                val rssi = result.level

                bssidToSsidMap[bssid] = ssid
                sampleBuffer.getOrPut(bssid) { mutableListOf() }.add(rssi)
            }

            if (currentSampleCount < activeTargetSamples) {
                scanHandler.postDelayed({
                    if (isScanningActive) triggerWifiScan()
                }, 1000)
            } else {
                processAndSaveAveragedFingerprint()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            log("Requesting permissions...\n")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
        } else {
            log("All permissions already granted!\n")
        }
    }

    private fun triggerWifiScan() {
        val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isLocationOn = locManager != null && LocationManagerCompat.isLocationEnabled(locManager)
        if (!isLocationOn) {
            log("❌ Device Location (GPS) is OFF! Turn ON Location in phone quick settings.\n")
            isScanningActive = false
            btnSurvey.isEnabled = true
            btnLocate.isEnabled = true
            btnClearData.isEnabled = true
            btnSettings.isEnabled = true
            btnViewMatrix.isEnabled = true
            releaseScreenWakeLock()
            return
        }

        if (!wifiManager.isWifiEnabled) {
            log("❌ Wi-Fi is OFF! Please turn ON Wi-Fi on your device.\n")
            isScanningActive = false
            btnSurvey.isEnabled = true
            btnLocate.isEnabled = true
            btnClearData.isEnabled = true
            btnSettings.isEnabled = true
            btnViewMatrix.isEnabled = true
            releaseScreenWakeLock()
            return
        }

        if (!isSurveying) {
            log("Starting Wi-Fi scan...\n")
        }

        val success = wifiManager.startScan()
        if (!success) {
            scanHandler.postDelayed({
                if (isScanningActive) triggerWifiScan()
            }, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editSpotName = findViewById(R.id.editSpotName)
        scrollView = findViewById(R.id.scrollView)
        tvLogs = findViewById(R.id.tvLogs)
        btnSurvey = findViewById(R.id.btnSurvey)
        btnLocate = findViewById(R.id.btnLocate)
        btnClearData = findViewById(R.id.btnClearData)
        btnSettings = findViewById(R.id.btnSettings)
        btnViewMatrix = findViewById(R.id.btnViewMatrix)
        tvSettingsSummary = findViewById(R.id.tvSettingsSummary)
        progressBar = findViewById(R.id.progressBar)
        tvProgressLabel = findViewById(R.id.tvProgressLabel)
        tvProgressText = findViewById(R.id.tvProgressText)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        log("App initialized.\n")
        checkAndRequestPermissions()
        loadSettings() // Load user-configured parameters & strategy mode from SharedPreferences
        loadFingerprintsFromCsv() // Load saved spots from CSV and build strategy models
        updateProgressDisplay(0, surveySampleCount, "Survey Progress")

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        // 0. Settings Button: Configure survey/locate counts, k-NN k, and strategy selection
        btnSettings.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }
            showSettingsDialog()
        }

        // View Matrix Button: Inspect the loaded 3D Fuzzy Matrix
        btnViewMatrix.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }
            showFuzzyMatrixDialog()
        }

        // 1. Survey Button: Record a labelled spot
        btnSurvey.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }

            val spot = editSpotName.text.toString().trim()
            if (spot.isEmpty()) {
                log("\n⚠️ Please enter a Spot Name (e.g. 'Spot 1') first!\n")
                return@setOnClickListener
            }

            activeTargetSamples = surveySampleCount
            isScanningActive = true
            btnSurvey.isEnabled = false
            btnLocate.isEnabled = false
            btnClearData.isEnabled = false
            btnSettings.isEnabled = false
            btnViewMatrix.isEnabled = false

            isSurveying = true
            acquireScreenWakeLock()
            sampleBuffer.clear()
            bssidToSsidMap.clear()
            currentSampleCount = 0

            updateProgressDisplay(0, activeTargetSamples, "Surveying '$spot'...")

            log("\n========================================\n")
            log("Survey started for '$spot' ($activeTargetSamples iterations)...\n")
            log("========================================\n")

            triggerWifiScan()
        }

        // 2. Locate Button: Measure live signals and run localization strategy
        btnLocate.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }

            if (surveyDatabase.isEmpty()) {
                log("\n⚠️ No spots saved! Survey at least one spot first.\n")
                return@setOnClickListener
            }

            activeTargetSamples = locateSampleCount
            isScanningActive = true
            btnSurvey.isEnabled = false
            btnLocate.isEnabled = false
            btnClearData.isEnabled = false
            btnSettings.isEnabled = false
            btnViewMatrix.isEnabled = false

            isSurveying = false
            sampleBuffer.clear()
            bssidToSsidMap.clear()
            currentSampleCount = 0

            updateProgressDisplay(0, activeTargetSamples, "Locating position...")

            log("\n========================================\n")
            val modeTitle = selectedStrategyMode.title
            log("📍 Locating position ($modeTitle, averaging $locateSampleCount scans)...\n")
            log("========================================\n")

            triggerWifiScan()
        }

        // 3. Clear Data Button: Delete all saved fingerprints and the CSV file
        btnClearData.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete All Survey Data?")
                .setMessage("Are you sure you want to permanently delete all saved spots from memory and remove $CSV_FILE_NAME?")
                .setPositiveButton("Yes, Delete") { _, _ ->
                    surveyDatabase.clear()
                    rebuildStrategyModels()
                    val file = File(getExternalFilesDir(null), CSV_FILE_NAME)
                    if (file.exists()) {
                        file.delete()
                    }
                    log("\n========================================\n")
                    log("🗑️ All survey data & CSV file have been deleted!\n")
                    log("Total spots in memory: 0\n")
                    log("========================================\n")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseScreenWakeLock()
        scanHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(wifiScanReceiver)
    }

    private fun log(message: String) {
        tvLogs.append(message)
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
