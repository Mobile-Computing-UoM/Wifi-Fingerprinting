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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.wififingerprint.data.BssidNormalizer
import com.example.wififingerprint.data.DeviceInfo
import com.example.wififingerprint.data.FingerprintFile
import com.example.wififingerprint.data.FingerprintFileMetadata
import com.example.wififingerprint.data.ReferenceRepository
import com.example.wififingerprint.share.ImportManager
import com.example.wififingerprint.share.ShareManager
import com.example.wififingerprint.calibration.CalibrationResult
import com.example.wififingerprint.calibration.CalibrationStore
import com.example.wififingerprint.calibration.DeviceCalibration
import com.example.wififingerprint.calibration.OffsetCalibrator
import com.example.wififingerprint.feedback.FeedbackCsv
import com.example.wififingerprint.feedback.FeedbackHistoryRecord
import com.example.wififingerprint.strategy.FingerprintSource
import com.example.wififingerprint.strategy.FuzzyMatrixStrategy
import com.example.wififingerprint.strategy.KnnStrategy
import com.example.wififingerprint.strategy.LiveQueryScan
import com.example.wififingerprint.strategy.LocalizationResult
import com.example.wififingerprint.strategy.PatternStrategy
import com.example.wififingerprint.strategy.RawApMeasurement
import com.example.wififingerprint.strategy.RawSurveySpot
import com.example.wififingerprint.strategy.ScanMode
import com.example.wififingerprint.strategy.StrategyMode
import com.google.android.material.tabs.TabLayout
import com.example.wififingerprint.experiment.ExperimentConfig
import com.example.wififingerprint.experiment.ExperimentCsvManager
import com.example.wififingerprint.experiment.ExperimentIterationResult
import com.example.wififingerprint.experiment.ExperimentListener
import com.example.wififingerprint.experiment.ExperimentRunner
import com.example.wififingerprint.experiment.ExperimentSummaryStats
import com.example.wififingerprint.experiment.ExperimentTabController
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
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
    private lateinit var tabLayout: TabLayout
    private lateinit var layoutScannerView: LinearLayout
    private lateinit var layoutSurveyAccView: LinearLayout
    private lateinit var layoutHistoryView: LinearLayout
    private lateinit var layoutExperimentView: LinearLayout

    // Tab 4: Automated Experiment Runner
    private lateinit var experimentCsvManager: ExperimentCsvManager
    private lateinit var experimentRunner: ExperimentRunner
    private lateinit var experimentController: ExperimentTabController

    // Tab 1: Survey Validation History
    private lateinit var tvSurveyAccStats: TextView
    private lateinit var btnRefreshSurveyAcc: Button
    private lateinit var btnDeleteAllSurveyAcc: Button
    private lateinit var tvEmptySurveyAcc: TextView
    private lateinit var rvSurveyAccHistory: RecyclerView
    private var surveyCalibAdapter: SurveyCalibrationAdapter? = null
    private val SURVEY_CALIB_CSV_NAME = "survey_calibration_history.csv"

    // Tab 2: Live Inference Feedback History
    private lateinit var tvHistoryStats: TextView
    private lateinit var btnRefreshHistory: Button
    private lateinit var btnDeleteAllHistory: Button
    private lateinit var tvEmptyHistory: TextView
    private lateinit var rvFeedbackHistory: RecyclerView
    private var historyAdapter: FeedbackHistoryAdapter? = null

    private lateinit var editSpotName: EditText
    private lateinit var editSurveyCoordX: EditText
    private lateinit var editSurveyCoordY: EditText
    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnSurvey: Button
    private lateinit var btnStopSurvey: Button
    private lateinit var btnLocate: Button
    private lateinit var btnClearData: Button
    private lateinit var btnSettings: Button
    private lateinit var btnViewMatrix: Button
    private lateinit var btnShareMap: Button
    private lateinit var btnImportMap: Button
    private lateinit var btnCalibrate: Button
    private lateinit var rgMapSource: RadioGroup
    private lateinit var rbSourceLocal: RadioButton
    private lateinit var rbSourceImported: RadioButton
    private var targetCalibSpot: String = ""
    private lateinit var tvSettingsSummary: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvProgressText: TextView
    private var userRequestedStop = false

    private lateinit var wifiManager: WifiManager
    private val surveyDatabase = mutableListOf<RawSurveySpot>()
    private val importedDatabase = mutableListOf<RawSurveySpot>()
    private var importedMetadata: FingerprintFileMetadata? = null
    private var activeMapSource: FingerprintSource = FingerprintSource.LOCAL
    private lateinit var referenceRepository: ReferenceRepository
    private lateinit var importManager: ImportManager

    private val CSV_FILE_NAME = "wifi_fingerprints.csv"
    private val FEEDBACK_CSV_NAME = "localization_feedback.csv"

    // Launcher for selecting an exported Wi-Fi map to import as read-only reference
    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportUri(uri)
        }
    }

    private fun anonymiseApIfNeeded(rawBssid: String, rawSsid: String): Pair<String, String> {
        val norm = BssidNormalizer.normalizeBssid(rawBssid)
        val displayLabel = BssidNormalizer.getDisplayLabel(rawBssid, rawSsid)
        return Pair(norm, displayLabel)
    }

    // Strategy Pattern instances
    private var selectedStrategyMode: StrategyMode = StrategyMode.BOTH
    private val knnStrategy = KnnStrategy(1)
    private val fuzzyMatrixStrategy = FuzzyMatrixStrategy()
    private val patternStrategy = PatternStrategy(minSharedAps = 2)

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
    private var surveySampleCount = 50    // Scans per survey spot
    private var locateSampleCount = 10    // Scans for locate
    private var knnK = 1                 // k for k-NN matching
    private var activeTargetSamples = 50 // Current active target
    private var testSplitPercent = 30    // Default 30% held-out test split
    private var isAutoCalibrationEnabled = false
    private var autoCalibTarget = 80     // Target accuracy %
    private var autoCalibMaxExtraScans = 0 // 0 = Unlimited, or positive integer for max extra scans
    private var autoCalibStrategy = "FUZZY_MATRIX" // "FUZZY_MATRIX" or "KNN"

    private var currentSampleCount = 0
    private var currentScanMode: ScanMode = ScanMode.SURVEY
    private val isSurveying: Boolean get() = currentScanMode == ScanMode.SURVEY
    private var isScanningActive = false // Only true when user explicitly pressed a button

    private val sampleBuffer = mutableMapOf<String, MutableList<Int>>()
    private val bssidToSsidMap = mutableMapOf<String, String>()
    private val scanSnapshots = mutableListOf<Map<String, Int>>()
    private val scanHandler = Handler(Looper.getMainLooper())

    private fun getActiveSurveyDatabase(): List<RawSurveySpot> {
        return if (activeMapSource == FingerprintSource.IMPORTED) {
            importedDatabase
        } else {
            surveyDatabase
        }
    }

    private fun rebuildStrategyModels() {
        val activeSpots = getActiveSurveyDatabase()
        knnStrategy.k = knnK
        knnStrategy.buildModel(activeSpots)
        fuzzyMatrixStrategy.buildModel(activeSpots)
        patternStrategy.buildModel(activeSpots)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        surveySampleCount = prefs.getInt("survey_count", 50)
        locateSampleCount = prefs.getInt("locate_count", 10)
        knnK = prefs.getInt("knn_k", 1)
        testSplitPercent = prefs.getInt("test_split_percent", 30)
        isAutoCalibrationEnabled = prefs.getBoolean("auto_calib_enabled", false)
        autoCalibTarget = prefs.getInt("auto_calib_target", 80)
        autoCalibMaxExtraScans = prefs.getInt("auto_calib_max_extra", 0)
        autoCalibStrategy = prefs.getString("auto_calib_strategy", "FUZZY_MATRIX") ?: "FUZZY_MATRIX"
        selectedStrategyMode = StrategyMode.fromKey(prefs.getString("strategy_mode", StrategyMode.BOTH.key))
        knnStrategy.k = knnK
        updateSettingsSummary()
    }

    private fun saveSettings(
        survey: Int,
        locate: Int,
        knn: Int,
        mode: StrategyMode,
        testSplit: Int,
        autoCalib: Boolean,
        targetAcc: Int,
        calibStrat: String,
        maxExtraScans: Int
    ) {
        surveySampleCount = survey
        locateSampleCount = locate
        knnK = knn
        selectedStrategyMode = mode
        testSplitPercent = testSplit
        isAutoCalibrationEnabled = autoCalib
        autoCalibTarget = targetAcc
        autoCalibMaxExtraScans = maxExtraScans
        autoCalibStrategy = calibStrat
        knnStrategy.k = knnK

        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("survey_count", survey)
            putInt("locate_count", locate)
            putInt("knn_k", knn)
            putString("strategy_mode", mode.key)
            putInt("test_split_percent", testSplit)
            putBoolean("auto_calib_enabled", autoCalib)
            putInt("auto_calib_target", targetAcc)
            putInt("auto_calib_max_extra", maxExtraScans)
            putString("auto_calib_strategy", calibStrat)
            apply()
        }
        updateSettingsSummary()
        if (!isScanningActive) {
            updateProgressDisplay(0, surveySampleCount, "Survey Progress")
        }
        val limitDesc = if (maxExtraScans > 0) "+$maxExtraScans max" else "unlimited"
        val calibInfo = if (autoCalib) "ON ($targetAcc% via $calibStrat, $limitDesc)" else "OFF"
        log("\n⚙️ Settings saved: Survey=$survey (${100 - testSplit}/$testSplit split) | Locate=$locate | Scan k-NN (k=$knn) | Auto-Calib=$calibInfo | Strategy=${mode.title}\n")
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
            StrategyMode.KNN -> "Scan k-NN (k=$knnK)"
            StrategyMode.FUZZY_MATRIX -> "Fuzzy Matrix"
            StrategyMode.PATTERN -> "Cross-Device Pattern"
            StrategyMode.BOTH -> "k-NN vs Fuzzy Matrix"
            StrategyMode.ALL -> "All 3 (Side-by-Side)"
        }
        val calibInfo = if (isAutoCalibrationEnabled) {
            val calibStrat = if (autoCalibStrategy == "FUZZY_MATRIX") "Fuzzy" else "k-NN"
            val limitDesc = if (autoCalibMaxExtraScans > 0) "+$autoCalibMaxExtraScans max" else "unlimited"
            "ON (≥$autoCalibTarget% via $calibStrat, $limitDesc)"
        } else {
            "OFF"
        }
        val sourceDesc = if (activeMapSource == FingerprintSource.IMPORTED) {
            val dev = importedMetadata?.model ?: "Imported Ref"
            val refId = importedMetadata?.deviceId ?: ""
            val calib = if (refId.isNotEmpty()) CalibrationStore.getCalibration(this, refId) else null
            val calibTag = if (calib != null) {
                val sign = if (calib.offsetDb >= 0) "+" else ""
                " [🎯 Calib: $sign${"%.1f".format(Locale.US, calib.offsetDb)} dB]"
            } else {
                " [⚠️ Uncalibrated]"
            }
            "Map: 📥 $dev (${importedDatabase.size} spots)$calibTag"
        } else {
            "Map: 📱 Local (${surveyDatabase.size} spots)"
        }
        tvSettingsSummary.text = "$sourceDesc | Survey=$surveySampleCount | Locate=$locateSampleCount | Mode: $stratShort"

        // Dynamically update locate button label
        btnLocate.text = when (selectedStrategyMode) {
            StrategyMode.KNN -> "2. Where Am I? (Scan k-NN)"
            StrategyMode.FUZZY_MATRIX -> "2. Where Am I? (Fuzzy Matrix)"
            StrategyMode.PATTERN -> "2. Where Am I? (Pattern Match)"
            StrategyMode.BOTH -> "2. Where Am I? (Compare 2)"
            StrategyMode.ALL -> "2. Where Am I? (Compare All 3)"
        }
    }

    private fun showCalibrationDialog() {
        val activeSpots = importedDatabase.map { it.spotLabel }.distinct().sorted()
        if (activeSpots.isEmpty()) {
            Toast.makeText(this, "No imported reference spots found to calibrate against!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_calibration, null, false)
        val spinnerSpot = dialogView.findViewById<Spinner>(R.id.spinnerCalibSpot)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tvCalibInfo)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, activeSpots)
        spinnerSpot.adapter = adapter

        val refId = importedMetadata?.deviceId ?: ""
        val existingCalib = if (refId.isNotEmpty()) CalibrationStore.getCalibration(this, refId) else null
        if (existingCalib != null) {
            val sign = if (existingCalib.offsetDb >= 0) "+" else ""
            tvInfo.text = "ℹ️ Current calibration: $sign${"%.1f".format(Locale.US, existingCalib.offsetDb)} dB (calibrated at '${existingCalib.spotLabel}' with ${existingCalib.sharedAps} APs).\nSelecting a spot and tapping 'Start' will recalibrate."
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎯 One-Spot Antenna Calibration")
            .setView(dialogView)
            .setPositiveButton("Start Calibration (10 scans)") { _, _ ->
                val selectedSpot = spinnerSpot.selectedItem?.toString() ?: activeSpots.first()
                startCalibrationScan(selectedSpot)
            }
            .setNegativeButton("Cancel", null)

        if (existingCalib != null) {
            builder.setNeutralButton("Reset Offset") { _, _ ->
                CalibrationStore.clearCalibration(this, refId)
                applySourceMode(FingerprintSource.IMPORTED)
                log("🎯 Cleared calibration offset for reference device: $refId\n")
                Toast.makeText(this, "Calibration reset to 0 dB", Toast.LENGTH_SHORT).show()
            }
        }

        builder.show()
    }

    private fun startCalibrationScan(spotLabel: String) {
        targetCalibSpot = spotLabel
        currentScanMode = ScanMode.CALIBRATE
        isScanningActive = true
        userRequestedStop = false

        btnSurvey.isEnabled = false
        btnLocate.isEnabled = false
        btnCalibrate.isEnabled = false
        btnClearData.isEnabled = false
        btnSettings.isEnabled = false
        btnViewMatrix.isEnabled = false
        btnStopSurvey.visibility = View.GONE

        acquireScreenWakeLock()
        sampleBuffer.clear()
        bssidToSsidMap.clear()
        scanSnapshots.clear()
        currentSampleCount = 0
        activeTargetSamples = 10

        updateProgressDisplay(0, activeTargetSamples, "Calibrating at '$spotLabel'...")

        log("\n========================================\n")
        log("🎯 ONE-SPOT ANTENNA CALIBRATION STARTED\n")
        log("• Reference Spot: '$spotLabel'\n")
        log("• Number of Scans: $activeTargetSamples\n")
        log("• Hold phone still at this physical spot...\n")
        log("========================================\n")

        triggerWifiScan()
    }

    private fun showCalibrationResultDialog(result: CalibrationResult) {
        if (!result.isValid) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠️ Calibration Failed")
                .setMessage(result.warningMessage ?: "Insufficient shared APs with reference spot '${result.spotLabel}'.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val refId = importedMetadata?.deviceId ?: "unknown"
        val diffDetails = result.perApDiffs.joinToString("\n") {
            val ssid = bssidToSsidMap[it.bssid] ?: "AP"
            val sign = if (it.diffDb >= 0) "+" else ""
            "• $ssid (${it.bssid.takeLast(8)}): Live ${it.liveRssi} dBm vs Ref ${it.refRssi} dBm ($sign${it.diffDb} dB)"
        }

        val msg = StringBuilder()
        msg.append("Reference Spot: ${result.spotLabel}\n")
        msg.append("Shared APs: ${result.sharedApCount}\n\n")
        val sign = if (result.medianOffsetDb >= 0) "+" else ""
        msg.append("Computed Antenna Bias:\n")
        msg.append("• Median Offset: $sign${"%.1f".format(Locale.US, result.medianOffsetDb)} dB\n")
        msg.append("• Mean Offset:   $sign${"%.1f".format(Locale.US, result.meanOffsetDb)} dB\n")
        msg.append("• StdDev (Spread): ${"%.1f".format(Locale.US, result.stdDevDb)} dB\n\n")

        if (result.warningMessage != null) {
            msg.append("⚠️ ${result.warningMessage}\n\n")
        }

        msg.append("AP Differences (Live - Ref):\n")
        msg.append(diffDetails)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎯 Calibration Results")
            .setMessage(msg.toString())
            .setPositiveButton("✅ Save & Apply Offset") { _, _ ->
                val calib = DeviceCalibration(
                    refDeviceId = refId,
                    offsetDb = result.medianOffsetDb,
                    spotLabel = result.spotLabel,
                    sharedAps = result.sharedApCount,
                    stdDevDb = result.stdDevDb,
                    timestampMs = System.currentTimeMillis()
                )
                CalibrationStore.saveCalibration(this, calib)
                applySourceMode(FingerprintSource.IMPORTED)

                log("\n========================================\n")
                log("🎯 CALIBRATION OFFSET APPLIED\n")
                log("• Reference Device: $refId\n")
                log("• Calibrated Spot: '${result.spotLabel}' (${result.sharedApCount} shared APs)\n")
                log("• Median Offset: $sign${"%.1f".format(Locale.US, result.medianOffsetDb)} dB\n")
                log("• Signals on this phone will be shifted by -$sign${"%.1f".format(Locale.US, result.medianOffsetDb)} dB for k-NN and Fuzzy matching\n")
                log("========================================\n\n")
                Toast.makeText(this, "Offset applied: $sign${"%.1f".format(Locale.US, result.medianOffsetDb)} dB", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Discard", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val editSurvey = dialogView.findViewById<EditText>(R.id.editSurveyCount)
        val editLocate = dialogView.findViewById<EditText>(R.id.editLocateCount)
        val editKnn = dialogView.findViewById<EditText>(R.id.editKnnK)
        val editTestSplit = dialogView.findViewById<EditText>(R.id.editTestSplitPercent)

        val cbAutoCalib = dialogView.findViewById<CheckBox>(R.id.cbAutoCalibration)
        val layoutAutoCalib = dialogView.findViewById<LinearLayout>(R.id.layoutAutoCalibSettings)
        val editTargetAcc = dialogView.findViewById<EditText>(R.id.editAutoCalibTarget)
        val editMaxLimit = dialogView.findViewById<EditText>(R.id.editAutoCalibMaxLimit)
        val rbAutoFuzzy = dialogView.findViewById<RadioButton>(R.id.rbAutoCalibFuzzy)
        val rbAutoKnn = dialogView.findViewById<RadioButton>(R.id.rbAutoCalibKnn)

        val rbBoth = dialogView.findViewById<RadioButton>(R.id.rbStrategyBoth)
        val rbKnn = dialogView.findViewById<RadioButton>(R.id.rbStrategyKnn)
        val rbFuzzy = dialogView.findViewById<RadioButton>(R.id.rbStrategyFuzzy)
        val rbPattern = dialogView.findViewById<RadioButton>(R.id.rbStrategyPattern)
        val rbAll = dialogView.findViewById<RadioButton>(R.id.rbStrategyAll)

        editSurvey.setText(surveySampleCount.toString())
        editLocate.setText(locateSampleCount.toString())
        editKnn.setText(knnK.toString())
        editTestSplit.setText(testSplitPercent.toString())

        cbAutoCalib.isChecked = isAutoCalibrationEnabled
        layoutAutoCalib.visibility = if (isAutoCalibrationEnabled) View.VISIBLE else View.GONE
        cbAutoCalib.setOnCheckedChangeListener { _, isChecked ->
            layoutAutoCalib.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        editTargetAcc.setText(autoCalibTarget.toString())
        editMaxLimit.setText(autoCalibMaxExtraScans.toString())
        if (autoCalibStrategy == "KNN") {
            rbAutoKnn.isChecked = true
        } else {
            rbAutoFuzzy.isChecked = true
        }

        when (selectedStrategyMode) {
            StrategyMode.BOTH -> rbBoth.isChecked = true
            StrategyMode.KNN -> rbKnn.isChecked = true
            StrategyMode.FUZZY_MATRIX -> rbFuzzy.isChecked = true
            StrategyMode.PATTERN -> rbPattern?.isChecked = true
            StrategyMode.ALL -> rbAll?.isChecked = true
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Configure Parameters & Auto-Calibration")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sCount = editSurvey.text.toString().toIntOrNull() ?: surveySampleCount
                val lCount = editLocate.text.toString().toIntOrNull() ?: locateSampleCount
                val kVal = editKnn.text.toString().toIntOrNull() ?: knnK
                val splitVal = (editTestSplit.text.toString().toIntOrNull() ?: testSplitPercent).coerceIn(10, 50)
                val targetAcc = (editTargetAcc.text.toString().toIntOrNull() ?: autoCalibTarget).coerceIn(50, 100)
                val maxLimitVal = (editMaxLimit.text.toString().toIntOrNull() ?: autoCalibMaxExtraScans).coerceAtLeast(0)
                val calibStrat = if (rbAutoKnn.isChecked) "KNN" else "FUZZY_MATRIX"

                val chosenMode = when {
                    rbKnn.isChecked -> StrategyMode.KNN
                    rbFuzzy.isChecked -> StrategyMode.FUZZY_MATRIX
                    rbPattern?.isChecked == true -> StrategyMode.PATTERN
                    rbAll?.isChecked == true -> StrategyMode.ALL
                    else -> StrategyMode.BOTH
                }

                if (sCount <= 0 || lCount <= 0 || kVal <= 0) {
                    Toast.makeText(this, "Parameters must be positive integers!", Toast.LENGTH_SHORT).show()
                } else {
                    saveSettings(sCount, lCount, kVal, chosenMode, splitVal, cbAutoCalib.isChecked, targetAcc, calibStrat, maxLimitVal)
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

        when (currentScanMode) {
            ScanMode.SURVEY -> {
                val spot = editSpotName.text.toString().trim()
                val totalScans = scanSnapshots.size
                val testScansCount = if (totalScans >= 2) maxOf(1, (totalScans * testSplitPercent) / 100) else 0
                val trainScansCount = maxOf(1, totalScans - testScansCount)

                val trainSnapshots = scanSnapshots.take(trainScansCount)
                val testSnapshots = scanSnapshots.drop(trainScansCount)

                // Build candidate spot model from train scans only
                val trainApMap = mutableMapOf<String, MutableList<Int>>()
                for (scan in trainSnapshots) {
                    for ((bssid, rssi) in scan) {
                        trainApMap.getOrPut(bssid) { mutableListOf() }.add(rssi)
                    }
                }

                val trainMeasurements = trainApMap.map { (bssid, readings) ->
                    RawApMeasurement(
                        bssid = bssid,
                        ssid = bssidToSsidMap[bssid] ?: "Unknown",
                        rawReadings = readings,
                        avgRssi = readings.average().roundToInt()
                    )
                }
                val candidateSpot = RawSurveySpot(spot, trainMeasurements, trainScansCount)
                val candidateDatabase = surveyDatabase.filter { !it.spotLabel.equals(spot, ignoreCase = true) } + candidateSpot

                val evalKnnStrategy = KnnStrategy(knnK)
                evalKnnStrategy.buildModel(candidateDatabase)
                val evalFuzzyStrategy = FuzzyMatrixStrategy()
                evalFuzzyStrategy.buildModel(candidateDatabase)

                var knnCorrect = 0
                var fuzzyCorrect = 0
                for (testScan in testSnapshots) {
                    val query = LiveQueryScan(testScan, bssidToSsidMap)
                    val knnRes = evalKnnStrategy.infer(query)
                    val fuzzyRes = evalFuzzyStrategy.infer(query)

                    if (knnRes.predictedSpot == spot) knnCorrect++
                    if (fuzzyRes.predictedSpot == spot) fuzzyCorrect++
                }

                val knnAcc = if (testScansCount > 0) (knnCorrect.toDouble() / testScansCount) * 100.0 else 100.0
                val fuzzyAcc = if (testScansCount > 0) (fuzzyCorrect.toDouble() / testScansCount) * 100.0 else 100.0
                val benchmarkAcc = if (autoCalibStrategy == "FUZZY_MATRIX") fuzzyAcc else knnAcc

                // Auto-Calibration condition check (skipped if user explicitly requested stop)
                val extraScansDone = activeTargetSamples - surveySampleCount
                val canExtend = if (autoCalibMaxExtraScans > 0) {
                    extraScansDone < autoCalibMaxExtraScans
                } else {
                    true // 0 = Unlimited!
                }

                if (isAutoCalibrationEnabled && !userRequestedStop && benchmarkAcc < autoCalibTarget && canExtend) {
                    val nextTarget = activeTargetSamples + 5
                    activeTargetSamples = nextTarget
                    val stratName = if (autoCalibStrategy == "FUZZY_MATRIX") "Fuzzy Matrix" else "Centroid k-NN"
                    val limitNotice = if (autoCalibMaxExtraScans > 0) " (Max limit: +$autoCalibMaxExtraScans extra scans)" else " (Unlimited mode)"
                    log("\n⚠️ Auto-Calibration: $stratName accuracy is ${"%.1f".format(benchmarkAcc)}% (< target $autoCalibTarget%).\n")
                    log("⚡ Extending survey: collecting +5 more scans (Target now $activeTargetSamples scans)$limitNotice...\n")
                    updateProgressDisplay(currentSampleCount, activeTargetSamples, "Auto-Calibrating '$spot'...")
                    scanHandler.postDelayed({
                        if (isScanningActive) triggerWifiScan()
                    }, 1000)
                    return
                }

                // Target met, Auto-Calib finished, or stopped by user: finalize survey
                val completionMsg = if (userRequestedStop) "Survey Stopped: '$spot'" else "Survey Completed: '$spot'"
                updateProgressDisplay(activeTargetSamples, activeTargetSamples, completionMsg)
                log("\n--- Survey Results for '$spot' ($activeTargetSamples iterations) ---\n")
                for (m in processedMeasurements) {
                    log("• ${m.ssid} (${m.bssid}): Avg ${m.avgRssi} dBm (${m.rawReadings.size} samples)\n")
                }

                // Save full measurements into database
                val rawMeasurements = processedMeasurements.map {
                    RawApMeasurement(it.bssid, it.ssid, it.rawReadings, it.avgRssi)
                }
                val coordX = editSurveyCoordX.text.toString().toDoubleOrNull() ?: 0.0
                val coordY = editSurveyCoordY.text.toString().toDoubleOrNull() ?: 0.0
                val spotRecord = RawSurveySpot(spot, rawMeasurements, activeTargetSamples, coordX, coordY)
                val existingIdx = surveyDatabase.indexOfFirst { it.spotLabel.equals(spot, ignoreCase = true) }
                if (existingIdx >= 0) {
                    surveyDatabase[existingIdx] = spotRecord
                } else {
                    surveyDatabase.add(spotRecord)
                }

                // Rebuild Strategy Models dynamically with the newly added spot
                rebuildStrategyModels()

                // Save to CSV file with all raw samples
                saveFingerprintToCsv(spot, processedMeasurements, coordX, coordY)
                if (::experimentController.isInitialized) {
                    experimentController.refreshSpotList()
                }

                val autoCalibStatus = if (userRequestedStop) {
                    "Stopped Early by User ($activeTargetSamples scans)"
                } else if (isAutoCalibrationEnabled) {
                    if (benchmarkAcc >= autoCalibTarget) {
                        "Target Met (${autoCalibTarget}%)"
                    } else if (autoCalibMaxExtraScans > 0 && extraScansDone >= autoCalibMaxExtraScans) {
                        "Capped at Limit (+$autoCalibMaxExtraScans scans)"
                    } else {
                        "Auto-Calibrated (${"%.1f".format(benchmarkAcc)}%)"
                    }
                } else {
                    "Disabled"
                }

                val timestamp = System.currentTimeMillis()
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val isoTime = sdf.format(Date(timestamp))

                val calibRecord = SurveyCalibrationRecord(
                    timestampMs = timestamp,
                    isoTime = isoTime,
                    spotLabel = spot,
                    totalScans = totalScans,
                    trainScans = trainScansCount,
                    testScans = testScansCount,
                    knnAccuracy = knnAcc,
                    fuzzyAccuracy = fuzzyAcc,
                    autoCalibrated = autoCalibStatus,
                    rawCsvLine = "$timestamp,\"$isoTime\",\"$spot\",$totalScans,$trainScansCount,$testScansCount,${"%.1f".format(knnAcc)},${"%.1f".format(fuzzyAcc)},\"$autoCalibStatus\""
                )
                saveSurveyCalibrationToCsv(calibRecord)

                log("\n========================================\n")
                log("📊 HELD-OUT VALIDATION RESULTS for '$spot'\n")
                log("========================================\n")
                log("• Total Scans: $totalScans ($trainScansCount Build / $testScansCount Held-Out Test)\n")
                log("• Strategy 1 (Centroid k-NN):   ${"%.1f".format(knnAcc)}% ($knnCorrect/$testScansCount correct)\n")
                log("• Strategy 2 (Fuzzy Matrix):    ${"%.1f".format(fuzzyAcc)}% ($fuzzyCorrect/$testScansCount correct)\n")
                log("• Auto-Calibration:             $autoCalibStatus\n")
                log("💾 Saved spot to CSV & validation history to survey_calibration_history.csv\n")
                log("Total spots in memory: ${surveyDatabase.size}\n")
                log("========================================\n\n")

                refreshSurveyAccView()
            }
            ScanMode.CALIBRATE -> {
                updateProgressDisplay(activeTargetSamples, activeTargetSamples, "Calibration Completed")
                log("\n--- Calibration Scan at '$targetCalibSpot' ($activeTargetSamples iterations) ---\n")
                for (m in processedMeasurements) {
                    log("• ${m.ssid} (${m.bssid}): Avg ${m.avgRssi} dBm (${m.rawReadings.size} samples)\n")
                }

                val refSpot = importedDatabase.find { it.spotLabel.equals(targetCalibSpot, ignoreCase = true) }
                if (refSpot == null) {
                    log("❌ Error: Spot '$targetCalibSpot' not found in imported reference database.\n")
                    Toast.makeText(this, "Calibration error: spot not found", Toast.LENGTH_SHORT).show()
                } else {
                    val result = OffsetCalibrator.compute(averagedFingerprint, refSpot)
                    showCalibrationResultDialog(result)
                }
            }
            ScanMode.LOCATE -> {
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
            ScanMode.EXPERIMENT -> {
                // Experiment scan batches are processed iteratively by ExperimentRunner
            }
        }

        // FINISHED: Unlock scanning state and re-enable buttons
        unlockControlsAfterScan()
    }

    private fun unlockControlsAfterScan() {
        isScanningActive = false
        if (activeMapSource == FingerprintSource.IMPORTED) {
            btnSurvey.isEnabled = false
            editSpotName.isEnabled = false
            btnCalibrate.isEnabled = true
        } else {
            btnSurvey.isEnabled = true
            editSpotName.isEnabled = true
            btnCalibrate.isEnabled = false
        }
        btnLocate.isEnabled = true
        btnClearData.isEnabled = true
        btnSettings.isEnabled = true
        btnViewMatrix.isEnabled = true
        btnStopSurvey.visibility = View.GONE
        userRequestedStop = false
        releaseScreenWakeLock()
    }

    /**
     * Executes localization using the configured Strategy Pattern mode.
     * Evaluates algorithms to enable real-world side-by-side comparison
     * and prompts the user for ground-truth validation feedback.
     */
    private fun executeLocalization(query: LiveQueryScan) {
        val activeSpots = getActiveSurveyDatabase()
        if (activeSpots.isEmpty()) {
            val sourceName = if (activeMapSource == FingerprintSource.IMPORTED) "imported reference" else "local survey"
            log("\n⚠️ No survey spots in $sourceName database to compare against!\n")
            return
        }

        rebuildStrategyModels()

        // If using an imported reference map and calibration exists, apply offset to k-NN & Fuzzy
        val refId = if (activeMapSource == FingerprintSource.IMPORTED) importedMetadata?.deviceId ?: "" else ""
        val calib = if (refId.isNotEmpty()) CalibrationStore.getCalibration(this, refId) else null

        val queryForCentroid = if (calib != null) {
            val sign = if (calib.offsetDb >= 0) "+" else ""
            log("🎯 Applying antenna offset ($sign${"%.1f".format(Locale.US, calib.offsetDb)} dB) to k-NN & Fuzzy query...\n")
            OffsetCalibrator.apply(query, calib.offsetDb)
        } else {
            query
        }

        // Evaluate strategies: k-NN and Fuzzy use corrected query; Pattern always uses raw query
        val resKnn = knnStrategy.infer(queryForCentroid)
        val resFuzzy = fuzzyMatrixStrategy.infer(queryForCentroid)
        val resPattern = patternStrategy.infer(query)

        when (selectedStrategyMode) {
            StrategyMode.KNN -> {
                log(resKnn.logOutput)
            }

            StrategyMode.FUZZY_MATRIX -> {
                log(resFuzzy.logOutput)
            }

            StrategyMode.PATTERN -> {
                log(resPattern.logOutput)
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

            StrategyMode.ALL -> {
                log(resKnn.logOutput)
                log(resFuzzy.logOutput)
                log(resPattern.logOutput)

                val knnWinner = resKnn.predictedSpot ?: "None"
                val fuzzyWinner = resFuzzy.predictedSpot ?: "None"
                val patternWinner = resPattern.predictedSpot ?: "None"

                val summary = StringBuilder()
                summary.append("\n========================================\n")
                summary.append("🏆 TRI-STRATEGY COMPARISON (Same Scan Data)\n")
                summary.append("========================================\n")
                val knnGap = if (resKnn.confidenceMargin != null) " (Gap: ${"%.2f".format(resKnn.confidenceMargin)} dBm)" else ""
                val fuzzyMargin = if (resFuzzy.confidenceMargin != null) " (Winning Margin: +${"%.4f".format(resFuzzy.confidenceMargin)})" else ""
                val patMargin = if (resPattern.confidenceMargin != null) " (Separation: ${"%.2f".format(resPattern.confidenceMargin)} dB)" else ""

                summary.append("• Strategy 1 (k-NN):         $knnWinner$knnGap\n")
                summary.append("• Strategy 2 (Fuzzy Matrix): $fuzzyWinner$fuzzyMargin\n")
                summary.append("• Strategy 3 (Cross Pattern):$patternWinner$patMargin\n\n")

                val allAgree = (knnWinner == fuzzyWinner && fuzzyWinner == patternWinner)
                if (allAgree) {
                    summary.append("🎯 UNANIMOUS AGREEMENT: All 3 algorithms predicted '$knnWinner'\n")
                } else {
                    summary.append("📊 DIVERGENT PREDICTION: Pattern predicts '$patternWinner' vs k-NN '$knnWinner'\n")
                }
                summary.append("========================================\n\n")
                log(summary.toString())
            }
        }

        // Prompt user for ground-truth accuracy feedback and persist outcome
        showFeedbackDialog(query, resKnn, resFuzzy, resPattern, calib?.offsetDb)
    }

    /**
     * Interactive validation dialog asking user whether prediction is correct
     * and selecting the actual physical location to persist for empirical accuracy analysis.
     */
    private fun showFeedbackDialog(
        query: LiveQueryScan,
        resKnn: LocalizationResult,
        resFuzzy: LocalizationResult,
        resPattern: LocalizationResult,
        calibOffsetDb: Double? = null
    ) {
        val spotNames = getActiveSurveyDatabase().map { it.spotLabel }.distinct().sorted()
        if (spotNames.isEmpty()) return

        val knnWinner = resKnn.predictedSpot ?: "None"
        val fuzzyWinner = resFuzzy.predictedSpot ?: "None"
        val patternWinner = resPattern.predictedSpot ?: "None"

        val dialogView = layoutInflater.inflate(R.layout.dialog_feedback, null, false)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvFeedbackSummary)
        val spinnerActualSpot = dialogView.findViewById<Spinner>(R.id.spinnerActualSpot)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spotNames)
        spinnerActualSpot.adapter = adapter

        // Pre-select predicted spot according to active mode
        val preferredWinner = when (selectedStrategyMode) {
            StrategyMode.PATTERN -> patternWinner
            StrategyMode.KNN -> knnWinner
            else -> if (spotNames.contains(fuzzyWinner)) fuzzyWinner else patternWinner
        }
        val defaultSelection = if (spotNames.contains(preferredWinner)) preferredWinner else spotNames.first()
        val defaultIdx = spotNames.indexOf(defaultSelection)
        if (defaultIdx >= 0) {
            spinnerActualSpot.setSelection(defaultIdx)
        }

        val knnGapStr = if (resKnn.confidenceMargin != null) " (Gap: ${"%.2f".format(resKnn.confidenceMargin)} dBm)" else ""
        val fuzzyMarginStr = if (resFuzzy.confidenceMargin != null) " (Margin: +${"%.4f".format(resFuzzy.confidenceMargin)})" else ""
        val patMarginStr = if (resPattern.confidenceMargin != null) " (Sep: ${"%.2f".format(resPattern.confidenceMargin)} dB)" else ""

        tvSummary.text = "Inference Predictions:\n" +
                "• Strategy 1 (k-NN): $knnWinner$knnGapStr\n" +
                "• Strategy 2 (Fuzzy): $fuzzyWinner$fuzzyMarginStr\n" +
                "• Strategy 3 (Pattern): $patternWinner$patMarginStr\n\n" +
                "Please select your ACTUAL current location to evaluate and persist accuracy:"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎯 Validate Prediction Accuracy")
            .setView(dialogView)
            .setPositiveButton("✅ Confirm & Save") { _, _ ->
                val actualSpot = spinnerActualSpot.selectedItem?.toString() ?: defaultSelection
                val isKnnCorrect = actualSpot.equals(knnWinner, ignoreCase = true)
                val isFuzzyCorrect = actualSpot.equals(fuzzyWinner, ignoreCase = true)
                val isPatternCorrect = actualSpot.equals(patternWinner, ignoreCase = true)

                saveEvaluationRecord(actualSpot, resKnn, resFuzzy, resPattern, query, calibOffsetDb)
                if (historyAdapter != null) {
                    refreshHistoryView()
                }

                val feedbackMsg = StringBuilder()
                feedbackMsg.append("\n========================================\n")
                feedbackMsg.append("📝 USER VALIDATION PERSISTED\n")
                feedbackMsg.append("========================================\n")
                feedbackMsg.append("• Actual Ground Truth:  $actualSpot\n")
                val knnErrM = KnnStrategy.computeErrorMeters(actualSpot, knnWinner, knnStrategy.lastEstimatedCoords)
                val fuzzyErrM = KnnStrategy.computeErrorMeters(actualSpot, fuzzyWinner, null)
                val knnErrStr = if (knnErrM != null) " (Error: ${"%.2f".format(knnErrM)} m)" else ""
                val fuzzyErrStr = if (fuzzyErrM != null) " (Error: ${"%.2f".format(fuzzyErrM)} m)" else ""
                feedbackMsg.append("• Strategy 1 (k-NN):    $knnWinner -> ${if (isKnnCorrect) "✅ CORRECT" else "❌ WRONG"}$knnErrStr\n")
                feedbackMsg.append("• Strategy 2 (Fuzzy):   $fuzzyWinner -> ${if (isFuzzyCorrect) "✅ CORRECT" else "❌ WRONG"}$fuzzyErrStr\n")
                feedbackMsg.append("• Strategy 3 (Pattern): $patternWinner -> ${if (isPatternCorrect) "✅ CORRECT" else "❌ WRONG"}\n")
                if (calibOffsetDb != null) {
                    val sign = if (calibOffsetDb >= 0) "+" else ""
                    feedbackMsg.append("• Antenna Offset:       $sign${"%.1f".format(Locale.US, calibOffsetDb)} dB\n")
                }
                feedbackMsg.append("💾 Saved to $FEEDBACK_CSV_NAME for further analysis\n")
                feedbackMsg.append("========================================\n\n")
                log(feedbackMsg.toString())

                Toast.makeText(this, "Validation saved for '$actualSpot'!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    /**
     * Appends validation result to localization_feedback.csv using the 17-column format.
     */
    private fun saveEvaluationRecord(
        actualSpot: String,
        resKnn: LocalizationResult,
        resFuzzy: LocalizationResult,
        resPattern: LocalizationResult,
        query: LiveQueryScan,
        calibOffsetDb: Double?
    ) {
        try {
            val file = File(getExternalFilesDir(null), FEEDBACK_CSV_NAME)
            val timestamp = System.currentTimeMillis()
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val isoTime = isoFormat.format(Date(timestamp))

            val knnPred = resKnn.predictedSpot ?: "None"
            val knnCorrect = knnPred.equals(actualSpot, ignoreCase = true)
            val knnMargin = resKnn.confidenceMargin ?: 0.0

            val fuzzyPred = resFuzzy.predictedSpot ?: "None"
            val fuzzyCorrect = fuzzyPred.equals(actualSpot, ignoreCase = true)
            val fuzzyMargin = resFuzzy.confidenceMargin ?: 0.0

            val patternPred = resPattern.predictedSpot ?: "None"
            val patternCorrect = patternPred.equals(actualSpot, ignoreCase = true)
            val patternMargin = resPattern.confidenceMargin ?: 0.0

            val queryStr = query.readings.entries.joinToString(";") { "${it.key}:${it.value}" }

            val refModel = if (activeMapSource == FingerprintSource.IMPORTED) {
                importedMetadata?.model ?: ""
            } else {
                DeviceInfo.getDeviceModel()
            }

            val record = FeedbackHistoryRecord(
                timestampMs = timestamp,
                isoTime = isoTime,
                actualSpot = actualSpot,
                knnPredicted = knnPred,
                knnCorrect = knnCorrect,
                knnMargin = knnMargin,
                fuzzyPredicted = fuzzyPred,
                fuzzyCorrect = fuzzyCorrect,
                fuzzyMargin = fuzzyMargin,
                querySignals = queryStr,
                patternPredicted = patternPred,
                patternCorrect = patternCorrect,
                patternMargin = patternMargin,
                mapSource = activeMapSource.name,
                refDeviceModel = refModel,
                locateDeviceModel = DeviceInfo.getDeviceModel(),
                calibOffsetDb = calibOffsetDb
            )

            FeedbackCsv.append(file, record)
        } catch (e: Exception) {
            log("❌ Error writing feedback CSV: ${e.message}\n")
        }
    }

    /**
     * Displays a full scrollable dialog showing the complete 3D Fuzzy Matrix model
     * with high-contrast text visible in both Dark and Light mode.
     */
    private fun showFuzzyMatrixDialog() {
        if (!fuzzyMatrixStrategy.isModelLoaded()) {
            Toast.makeText(this, "Fuzzy Matrix is empty! Survey spots or load data first.", Toast.LENGTH_SHORT).show()
            return
        }

        val matrixText = fuzzyMatrixStrategy.getFormattedMatrix()
        val view = layoutInflater.inflate(R.layout.dialog_matrix_view, null, false)
        val tvMatrix = view.findViewById<TextView>(R.id.tvMatrixContent)
        tvMatrix.text = matrixText

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📊 3D Fuzzy Matrix View")
            .setView(view)
            .setPositiveButton("Close", null)
            .setNeutralButton("Print to Terminal") { _, _ ->
                log("\n" + matrixText + "\n")
            }
            .show()
    }

    /**
     * Parses a CSV line safely respecting quotation marks around fields.
     */
    private fun parseCsvLine(line: String): List<String> {
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
     * Loads all persisted inference evaluation records from localization_feedback.csv.
     */
    private fun loadFeedbackHistory(): List<FeedbackHistoryRecord> {
        val file = File(getExternalFilesDir(null), FEEDBACK_CSV_NAME)
        return FeedbackCsv.loadAll(file)
    }

    /**
     * Deletes a single specific row from localization_feedback.csv and updates UI.
     */
    private fun deleteFeedbackRecord(record: FeedbackHistoryRecord, position: Int) {
        val file = File(getExternalFilesDir(null), FEEDBACK_CSV_NAME)
        FeedbackCsv.delete(file, record.rawCsvLine)

        historyAdapter?.removeAt(position)
        updateHistoryStats()
        Toast.makeText(this, "Deleted history row for '${record.actualSpot}'", Toast.LENGTH_SHORT).show()
        log("🗑️ Deleted feedback history row for '${record.actualSpot}'\n")
    }

    /**
     * Updates header statistics for inference history.
     */
    private fun updateHistoryStats() {
        val count = historyAdapter?.itemCount ?: 0
        if (count == 0) {
            tvEmptyHistory.visibility = View.VISIBLE
            rvFeedbackHistory.visibility = View.GONE
            tvHistoryStats.text = "Inference History (0 records)"
        } else {
            tvEmptyHistory.visibility = View.GONE
            rvFeedbackHistory.visibility = View.VISIBLE
            val records = historyAdapter?.records ?: emptyList()
            val knnCorrectCount = records.count { it.knnCorrect }
            val fuzzyCorrectCount = records.count { it.fuzzyCorrect }
            val patCorrectCount = records.count { it.patternCorrect }
            val knnPct = (knnCorrectCount.toDouble() / count * 100).toInt()
            val fuzzyPct = (fuzzyCorrectCount.toDouble() / count * 100).toInt()
            val patPct = (patCorrectCount.toDouble() / count * 100).toInt()
            tvHistoryStats.text = "Records: $count | k-NN: $knnPct% | Fuzzy: $fuzzyPct% | Pattern: $patPct%"
        }
    }

    /**
     * Reloads and displays the inference history list.
     */
    private fun refreshHistoryView() {
        val records = loadFeedbackHistory().toMutableList()
        if (historyAdapter == null) {
            historyAdapter = FeedbackHistoryAdapter(records) { item, pos ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete Validation Record?")
                    .setMessage("Permanently remove this evaluation record for actual spot '${item.actualSpot}' from CSV?")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteFeedbackRecord(item, pos)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            rvFeedbackHistory.layoutManager = LinearLayoutManager(this)
            rvFeedbackHistory.adapter = historyAdapter
        } else {
            historyAdapter?.updateList(records)
        }
        updateHistoryStats()
    }

    /**
     * Appends a survey train/test calibration record to survey_calibration_history.csv.
     */
    private fun saveSurveyCalibrationToCsv(record: SurveyCalibrationRecord) {
        try {
            val file = File(getExternalFilesDir(null), SURVEY_CALIB_CSV_NAME)
            val isNewFile = !file.exists()
            val writer = FileWriter(file, true)

            if (isNewFile) {
                writer.append("timestamp_ms,iso_time,spot_label,total_scans,train_scans,test_scans,knn_acc,fuzzy_acc,auto_calib\n")
            }

            writer.append("${record.timestampMs},\"${record.isoTime}\",\"${record.spotLabel}\",${record.totalScans},${record.trainScans},${record.testScans},${"%.1f".format(record.knnAccuracy)},${"%.1f".format(record.fuzzyAccuracy)},\"${record.autoCalibrated}\"\n")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            log("❌ Error writing survey calibration CSV: ${e.message}\n")
        }
    }

    /**
     * Loads all persisted survey validation records from survey_calibration_history.csv.
     */
    private fun loadSurveyCalibrationHistory(): List<SurveyCalibrationRecord> {
        val file = File(getExternalFilesDir(null), SURVEY_CALIB_CSV_NAME)
        if (!file.exists()) return emptyList()

        val records = mutableListOf<SurveyCalibrationRecord>()
        file.forEachLine { line ->
            if (line.isNotBlank() && !line.startsWith("timestamp_ms")) {
                val parts = parseCsvLine(line)
                if (parts.size >= 9) {
                    val timestampMs = parts[0].toLongOrNull() ?: 0L
                    val isoTime = parts[1]
                    val spotLabel = parts[2]
                    val totalScans = parts[3].toIntOrNull() ?: 0
                    val trainScans = parts[4].toIntOrNull() ?: 0
                    val testScans = parts[5].toIntOrNull() ?: 0
                    val knnAcc = parts[6].toDoubleOrNull() ?: 0.0
                    val fuzzyAcc = parts[7].toDoubleOrNull() ?: 0.0
                    val autoCalib = parts[8]

                    records.add(
                        SurveyCalibrationRecord(
                            timestampMs = timestampMs,
                            isoTime = isoTime,
                            spotLabel = spotLabel,
                            totalScans = totalScans,
                            trainScans = trainScans,
                            testScans = testScans,
                            knnAccuracy = knnAcc,
                            fuzzyAccuracy = fuzzyAcc,
                            autoCalibrated = autoCalib,
                            rawCsvLine = line
                        )
                    )
                }
            }
        }
        return records.reversed()
    }

    /**
     * Deletes a single specific row from survey_calibration_history.csv and updates UI.
     */
    private fun deleteSurveyCalibrationRecord(record: SurveyCalibrationRecord, position: Int) {
        val file = File(getExternalFilesDir(null), SURVEY_CALIB_CSV_NAME)
        if (file.exists()) {
            val lines = file.readLines()
            val remainingLines = lines.filter { line ->
                line.startsWith("timestamp_ms") || line.trim() != record.rawCsvLine.trim()
            }
            file.writeText(remainingLines.joinToString("\n") + if (remainingLines.isNotEmpty()) "\n" else "")
        }

        surveyCalibAdapter?.removeAt(position)
        updateSurveyAccStats()
        Toast.makeText(this, "Deleted survey validation record for '${record.spotLabel}'", Toast.LENGTH_SHORT).show()
        log("🗑️ Deleted survey calibration row for '${record.spotLabel}'\n")
    }

    /**
     * Updates header statistics for survey calibration history.
     */
    private fun updateSurveyAccStats() {
        val count = surveyCalibAdapter?.itemCount ?: 0
        if (count == 0) {
            tvEmptySurveyAcc.visibility = View.VISIBLE
            rvSurveyAccHistory.visibility = View.GONE
            tvSurveyAccStats.text = "Survey Calibration (0 records)"
        } else {
            tvEmptySurveyAcc.visibility = View.GONE
            rvSurveyAccHistory.visibility = View.VISIBLE
            val records = surveyCalibAdapter?.records ?: emptyList()
            val avgKnn = records.map { it.knnAccuracy }.average()
            val avgFuzzy = records.map { it.fuzzyAccuracy }.average()
            tvSurveyAccStats.text = "Records: $count | Avg Centroid: ${"%.1f".format(avgKnn)}% | Avg Fuzzy: ${"%.1f".format(avgFuzzy)}%"
        }
    }

    /**
     * Reloads and displays the survey calibration list.
     */
    private fun refreshSurveyAccView() {
        val records = loadSurveyCalibrationHistory().toMutableList()
        if (surveyCalibAdapter == null) {
            surveyCalibAdapter = SurveyCalibrationAdapter(records) { item, pos ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete Calibration Record?")
                    .setMessage("Permanently remove this survey validation record for '${item.spotLabel}' from CSV?")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteSurveyCalibrationRecord(item, pos)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            rvSurveyAccHistory.layoutManager = LinearLayoutManager(this)
            rvSurveyAccHistory.adapter = surveyCalibAdapter
        } else {
            surveyCalibAdapter?.updateList(records)
        }
        updateSurveyAccStats()
    }

    // Save fingerprint entry into wifi_fingerprints.csv including ALL raw measurements
    // Replaces previous lines for this spot to keep CSV clean when continuing survey
    private fun saveFingerprintToCsv(spot: String, measurements: List<ApMeasurement>, coordX: Double = 0.0, coordY: Double = 0.0) {
        try {
            val file = File(getExternalFilesDir(null), CSV_FILE_NAME)
            val header = "timestamp,spot_label,coord_x,coord_y,bssid,ssid,sample_count,samples,avg_rssi"
            val remainingLines = if (file.exists()) {
                file.readLines().filter { line ->
                    if (line.isBlank() || line.startsWith("timestamp")) return@filter false
                    val parts = line.split(",")
                    val lineSpot = parts.getOrNull(1)?.trim() ?: ""
                    !lineSpot.equals(spot, ignoreCase = true)
                }
            } else emptyList()

            val writer = FileWriter(file, false) // rewrite with non-matching lines + updated spot lines
            writer.append("$header\n")
            for (line in remainingLines) {
                writer.append("$line\n")
            }

            val timestamp = System.currentTimeMillis()
            for (m in measurements) {
                val cleanSsid = m.ssid.replace(",", " ")
                val rawStr = "\"[" + m.rawReadings.joinToString(";") + "]\""
                writer.append("$timestamp,$spot,$coordX,$coordY,${m.bssid},$cleanSsid,${m.rawReadings.size},$rawStr,${m.avgRssi}\n")
            }

            writer.flush()
            writer.close()
        } catch (e: Exception) {
            log("❌ Error writing CSV: ${e.message}\n")
        }
    }

    // Load previously saved survey data from wifi_fingerprints.csv on startup
    // Supports both old 5-column, 7-column and new 9-column formats, reconstructing full raw readings
    private fun loadFingerprintsFromCsv() {
        try {
            val file = File(getExternalFilesDir(null), CSV_FILE_NAME)
            if (!file.exists()) return

            // Map: SpotLabel -> (BSSID -> RawApMeasurement)
            val spotMeasurements = mutableMapOf<String, MutableMap<String, RawApMeasurement>>()
            val spotMaxScans = mutableMapOf<String, Int>()
            val spotCoords = mutableMapOf<String, Pair<Double, Double>>()

            file.forEachLine { line ->
                if (!line.startsWith("timestamp") && line.isNotBlank()) {
                    if (line.contains("\"[")) {
                        // Raw sample array present
                        val parts = line.split("\"")
                        val meta = parts[0].split(",")
                        val hasCoords = meta.size >= 7 && meta[2].toDoubleOrNull() != null && meta[3].toDoubleOrNull() != null
                        val spot = meta.getOrNull(1)?.trim() ?: ""
                        val coordX = if (hasCoords) meta.getOrNull(2)?.trim()?.toDoubleOrNull() ?: 0.0 else 0.0
                        val coordY = if (hasCoords) meta.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 0.0 else 0.0
                        val bssid = if (hasCoords) meta.getOrNull(4)?.trim() ?: "" else meta.getOrNull(2)?.trim() ?: ""
                        val ssid = if (hasCoords) meta.getOrNull(5)?.trim() ?: "Unknown" else meta.getOrNull(3)?.trim() ?: "Unknown"
                        val count = if (hasCoords) meta.getOrNull(6)?.trim()?.toIntOrNull() ?: 1 else meta.getOrNull(4)?.trim()?.toIntOrNull() ?: 1

                        val rawList = if (parts.size > 1) {
                            parts[1].trim(' ', '[', ']')
                                .split(";")
                                .mapNotNull { it.toIntOrNull() }
                        } else emptyList()

                        val avg = parts.getOrNull(2)?.trim(',', ' ')?.toIntOrNull()
                            ?: (if (rawList.isNotEmpty()) rawList.average().roundToInt() else -100)

                        if (spot.isNotEmpty() && bssid.isNotEmpty()) {
                            val normBssid = BssidNormalizer.normalizeBssid(bssid)
                            val currentMax = spotMaxScans[spot] ?: 0
                            spotMaxScans[spot] = maxOf(currentMax, count, rawList.size)
                            if (hasCoords || !spotCoords.containsKey(spot)) {
                                spotCoords[spot] = Pair(coordX, coordY)
                            }
                            spotMeasurements.getOrPut(spot) { mutableMapOf() }[normBssid] =
                                RawApMeasurement(normBssid, ssid, rawList.ifEmpty { listOf(avg) }, avg)
                        }
                    } else {
                        // Legacy 5-column format: timestamp,spot_label,bssid,ssid,avg_rssi
                        val parts = line.split(",")
                        if (parts.size >= 5) {
                            val spot = parts[1].trim()
                            val bssid = parts[2].trim()
                            val ssid = parts[3].trim()
                            val rssi = parts.last().trim().toIntOrNull() ?: -100

                            if (spot.isNotEmpty() && bssid.isNotEmpty()) {
                                val normBssid = BssidNormalizer.normalizeBssid(bssid)
                                val currentMax = spotMaxScans[spot] ?: 0
                                spotMaxScans[spot] = maxOf(currentMax, 1)
                                spotMeasurements.getOrPut(spot) { mutableMapOf() }[normBssid] =
                                    RawApMeasurement(normBssid, ssid, listOf(rssi), rssi)
                            }
                        }
                    }
                }
            }

            surveyDatabase.clear()
            for ((spot, bssidMap) in spotMeasurements) {
                val totalScans = spotMaxScans[spot] ?: 1
                val coords = spotCoords[spot] ?: Pair(0.0, 0.0)
                surveyDatabase.add(RawSurveySpot(spot, bssidMap.values.toList(), totalScans, coords.first, coords.second))
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

            if (currentScanMode == ScanMode.EXPERIMENT) {
                val currentScanMap = mutableMapOf<String, Int>()
                for (result in scanResults) {
                    val rawBssid = result.BSSID ?: continue
                    val rawSsid = if (result.SSID.isNullOrEmpty()) "Hidden" else result.SSID
                    val (bssid, ssid) = anonymiseApIfNeeded(rawBssid, rawSsid)
                    val rssi = result.level
                    bssidToSsidMap[bssid] = ssid
                    currentScanMap[bssid] = rssi
                }
                experimentRunner.onScanResultsAvailable(currentScanMap, bssidToSsidMap)
                return
            }

            currentSampleCount++
            val statusMsg = when (currentScanMode) {
                ScanMode.SURVEY -> "Surveying '${editSpotName.text.toString().trim()}'..."
                ScanMode.CALIBRATE -> "Calibrating at '$targetCalibSpot'..."
                ScanMode.LOCATE -> "Locating..."
                ScanMode.EXPERIMENT -> "Experimenting..."
            }
            updateProgressDisplay(currentSampleCount, activeTargetSamples, statusMsg)

            val currentScanMap = mutableMapOf<String, Int>()
            for (result in scanResults) {
                val rawBssid = result.BSSID ?: continue
                val rawSsid = if (result.SSID.isNullOrEmpty()) "Hidden" else result.SSID
                val (bssid, ssid) = anonymiseApIfNeeded(rawBssid, rawSsid)
                val rssi = result.level

                bssidToSsidMap[bssid] = ssid
                sampleBuffer.getOrPut(bssid) { mutableListOf() }.add(rssi)
                currentScanMap[bssid] = rssi
            }
            if (currentScanMode == ScanMode.SURVEY) {
                scanSnapshots.add(currentScanMap)
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

    private fun handleImportUri(uri: Uri) {
        try {
            val result = importManager.importFromUri(uri)
            importedDatabase.clear()
            importedDatabase.addAll(result.spots)
            importedMetadata = result.metadata

            log("\n📥 Successfully imported reference map from '${result.metadata.model}' (Device ID: ${result.metadata.deviceId})!\n")
            log("📍 Loaded ${result.spots.size} reference spot(s) into local reference repository\n")

            // Switch source to IMPORTED
            rbSourceImported.isChecked = true
            applySourceMode(FingerprintSource.IMPORTED)
            Toast.makeText(this, "Imported ${result.spots.size} spots from ${result.metadata.model}!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            log("\n❌ Failed to import reference map: ${e.message}\n")
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applySourceMode(source: FingerprintSource) {
        activeMapSource = source
        if (source == FingerprintSource.IMPORTED) {
            if (importedDatabase.isEmpty()) {
                val latest = referenceRepository.loadLatestReference()
                if (latest != null) {
                    importedMetadata = latest.first
                    importedDatabase.clear()
                    importedDatabase.addAll(latest.second)
                }
            }

            if (importedDatabase.isEmpty()) {
                log("\n⚠️ No imported reference map found. Tap '📥 Import Ref' to import one from another phone.\n")
                Toast.makeText(this, "No imported reference found. Please import one.", Toast.LENGTH_SHORT).show()
                rbSourceLocal.isChecked = true
                activeMapSource = FingerprintSource.LOCAL
                applySourceMode(FingerprintSource.LOCAL)
                return
            }

            // In Imported mode:
            // Disable Surveying (survey database is read-only)
            btnSurvey.isEnabled = false
            btnSurvey.text = "🚫 Surveying Disabled (Imported Ref Mode)"
            editSpotName.isEnabled = false
            editSpotName.hint = "Locating via imported reference map (${importedDatabase.size} spots)"
            btnCalibrate.visibility = View.VISIBLE
            val refId = importedMetadata?.deviceId ?: ""
            val calib = if (refId.isNotEmpty()) CalibrationStore.getCalibration(this, refId) else null
            if (calib != null) {
                val sign = if (calib.offsetDb >= 0) "+" else ""
                btnCalibrate.text = "🎯 Calib ($sign${"%.1f".format(Locale.US, calib.offsetDb)} dB)"
            } else {
                btnCalibrate.text = "🎯 Calibrate"
            }
            log("🔄 Switched to Imported Reference Map: ${importedMetadata?.model} (${importedDatabase.size} spots)\n")
        } else {
            // Local mode
            btnSurvey.isEnabled = true
            btnSurvey.text = getString(R.string._1_record_survey_sample)
            editSpotName.isEnabled = true
            editSpotName.hint = getString(R.string.enter_spot_label_e_g_spot_1)
            btnCalibrate.visibility = View.GONE
            log("🔄 Switched to Local Device Survey Map (${surveyDatabase.size} spots)\n")
        }

        rebuildStrategyModels()
        updateSettingsSummary()
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

    private fun triggerWifiScan(): Boolean {
        val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isLocationOn = locManager != null && LocationManagerCompat.isLocationEnabled(locManager)
        if (!isLocationOn) {
            log("❌ Device Location (GPS) is OFF! Turn ON Location in phone quick settings.\n")
            unlockControlsAfterScan()
            return false
        }

        if (!wifiManager.isWifiEnabled) {
            log("❌ Wi-Fi is OFF! Please turn ON Wi-Fi on your device.\n")
            unlockControlsAfterScan()
            return false
        }

        if (currentScanMode == ScanMode.LOCATE) {
            log("Starting Wi-Fi scan...\n")
        }

        val success = wifiManager.startScan()
        if (!success) {
            scanHandler.postDelayed({
                if (isScanningActive) triggerWifiScan()
            }, 1500)
        }
        return success
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabLayout = findViewById(R.id.tabLayout)
        layoutScannerView = findViewById(R.id.layoutScannerView)
        layoutSurveyAccView = findViewById(R.id.layoutSurveyAccView)
        layoutHistoryView = findViewById(R.id.layoutHistoryView)
        layoutExperimentView = findViewById(R.id.layoutExperimentView)

        // Tab 1: Survey Validation History Views
        tvSurveyAccStats = findViewById(R.id.tvSurveyAccStats)
        btnRefreshSurveyAcc = findViewById(R.id.btnRefreshSurveyAcc)
        btnDeleteAllSurveyAcc = findViewById(R.id.btnDeleteAllSurveyAcc)
        tvEmptySurveyAcc = findViewById(R.id.tvEmptySurveyAcc)
        rvSurveyAccHistory = findViewById(R.id.rvSurveyAccHistory)

        // Tab 2: Live Inference Feedback History Views
        tvHistoryStats = findViewById(R.id.tvHistoryStats)
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        btnDeleteAllHistory = findViewById(R.id.btnDeleteAllHistory)
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory)
        rvFeedbackHistory = findViewById(R.id.rvFeedbackHistory)

        tabLayout.addTab(tabLayout.newTab().setText("📡 Scanner"))
        tabLayout.addTab(tabLayout.newTab().setText("📊 Survey Accuracy"))
        tabLayout.addTab(tabLayout.newTab().setText("📋 Live History"))
        tabLayout.addTab(tabLayout.newTab().setText("🔬 Experiment"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        layoutScannerView.visibility = View.VISIBLE
                        layoutSurveyAccView.visibility = View.GONE
                        layoutHistoryView.visibility = View.GONE
                        layoutExperimentView.visibility = View.GONE
                    }
                    1 -> {
                        layoutScannerView.visibility = View.GONE
                        layoutSurveyAccView.visibility = View.VISIBLE
                        layoutHistoryView.visibility = View.GONE
                        layoutExperimentView.visibility = View.GONE
                        refreshSurveyAccView()
                    }
                    2 -> {
                        layoutScannerView.visibility = View.GONE
                        layoutSurveyAccView.visibility = View.GONE
                        layoutHistoryView.visibility = View.VISIBLE
                        layoutExperimentView.visibility = View.GONE
                        refreshHistoryView()
                    }
                    3 -> {
                        layoutScannerView.visibility = View.GONE
                        layoutSurveyAccView.visibility = View.GONE
                        layoutHistoryView.visibility = View.GONE
                        layoutExperimentView.visibility = View.VISIBLE
                        if (::experimentController.isInitialized) {
                            experimentController.refreshSpotList()
                            experimentController.refreshHistory()
                        }
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    1 -> refreshSurveyAccView()
                    2 -> refreshHistoryView()
                    3 -> {
                        if (::experimentController.isInitialized) {
                            experimentController.refreshSpotList()
                            experimentController.refreshHistory()
                        }
                    }
                }
            }
        })

        btnRefreshSurveyAcc.setOnClickListener {
            refreshSurveyAccView()
        }

        btnDeleteAllSurveyAcc.setOnClickListener {
            val count = surveyCalibAdapter?.itemCount ?: 0
            if (count == 0) {
                Toast.makeText(this, "Survey calibration history is already empty!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete All Survey Calibration Records?")
                .setMessage("Are you sure you want to permanently delete ALL $count survey validation records? This will clear $SURVEY_CALIB_CSV_NAME.")
                .setPositiveButton("Yes, Delete All") { _, _ ->
                    val file = File(getExternalFilesDir(null), SURVEY_CALIB_CSV_NAME)
                    if (file.exists()) {
                        file.delete()
                    }
                    refreshSurveyAccView()
                    Toast.makeText(this, "All survey calibration records deleted!", Toast.LENGTH_SHORT).show()
                    log("🗑️ Deleted all survey calibration history ($count records) and cleared $SURVEY_CALIB_CSV_NAME\n")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnRefreshHistory.setOnClickListener {
            refreshHistoryView()
        }

        btnDeleteAllHistory.setOnClickListener {
            val count = historyAdapter?.itemCount ?: 0
            if (count == 0) {
                Toast.makeText(this, "Live history is already empty!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete All Live History?")
                .setMessage("Are you sure you want to permanently delete ALL $count live inference validation records? This will clear $FEEDBACK_CSV_NAME.")
                .setPositiveButton("Yes, Delete All") { _, _ ->
                    val file = File(getExternalFilesDir(null), FEEDBACK_CSV_NAME)
                    if (file.exists()) {
                        file.delete()
                    }
                    refreshHistoryView()
                    Toast.makeText(this, "All live history records deleted!", Toast.LENGTH_SHORT).show()
                    log("🗑️ Deleted all live inference validation history ($count records) and cleared $FEEDBACK_CSV_NAME\n")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        editSpotName = findViewById(R.id.editSpotName)
        editSurveyCoordX = findViewById(R.id.editSurveyCoordX)
        editSurveyCoordY = findViewById(R.id.editSurveyCoordY)
        scrollView = findViewById(R.id.scrollView)
        tvLogs = findViewById(R.id.tvLogs)
        btnSurvey = findViewById(R.id.btnSurvey)
        btnStopSurvey = findViewById(R.id.btnStopSurvey)
        btnLocate = findViewById(R.id.btnLocate)
        btnClearData = findViewById(R.id.btnClearData)
        btnSettings = findViewById(R.id.btnSettings)
        btnViewMatrix = findViewById(R.id.btnViewMatrix)
        btnShareMap = findViewById(R.id.btnShareMap)
        btnImportMap = findViewById(R.id.btnImportMap)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        rgMapSource = findViewById(R.id.rgMapSource)
        rbSourceLocal = findViewById(R.id.rbSourceLocal)
        rbSourceImported = findViewById(R.id.rbSourceImported)

        referenceRepository = ReferenceRepository(this)
        importManager = ImportManager(this)

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

        experimentCsvManager = ExperimentCsvManager(this)

        experimentRunner = ExperimentRunner(
            knnStrategy = knnStrategy,
            fuzzyMatrixStrategy = fuzzyMatrixStrategy,
            csvManager = experimentCsvManager,
            listener = object : ExperimentListener {
                override fun onScanProgress(trialIndex: Int, totalTrials: Int, scanIndex: Int, totalScans: Int) {
                    runOnUiThread {
                        experimentController.onScanProgress(trialIndex, totalTrials, scanIndex, totalScans)
                    }
                }

                override fun onTrialCompleted(result: ExperimentIterationResult, stats: ExperimentSummaryStats) {
                    runOnUiThread {
                        experimentController.onTrialCompleted(result, stats)
                    }
                }

                override fun onExperimentFinished(stats: ExperimentSummaryStats) {
                    runOnUiThread {
                        unlockControlsAfterScan()
                        experimentController.onExperimentFinished(stats)
                    }
                }

                override fun onExperimentAborted(completedTrials: Int) {
                    runOnUiThread {
                        unlockControlsAfterScan()
                        experimentController.onExperimentAborted()
                    }
                }

                override fun onLog(message: String) {
                    runOnUiThread {
                        log(message)
                    }
                }

                override fun requestWifiScan(): Boolean {
                    return triggerWifiScan()
                }
            },
            queryPreprocessor = { rawQuery ->
                val refId = if (activeMapSource == FingerprintSource.IMPORTED) importedMetadata?.deviceId ?: "" else ""
                val calib = if (refId.isNotEmpty()) CalibrationStore.getCalibration(this, refId) else null
                if (calib != null) {
                    OffsetCalibrator.apply(rawQuery, calib.offsetDb)
                } else {
                    rawQuery
                }
            }
        )

        experimentController = ExperimentTabController(
            activity = this,
            rootView = layoutExperimentView,
            csvManager = experimentCsvManager,
            getAvailableSpots = { getActiveSurveyDatabase() },
            onStartExperimentRequested = { config ->
                if (isScanningActive) {
                    Toast.makeText(this, "Scan in progress, please wait", Toast.LENGTH_SHORT).show()
                    return@ExperimentTabController
                }
                val activeSpots = getActiveSurveyDatabase()
                if (activeSpots.isEmpty()) {
                    Toast.makeText(this, "Please survey spots first!", Toast.LENGTH_LONG).show()
                    return@ExperimentTabController
                }

                isScanningActive = true
                currentScanMode = ScanMode.EXPERIMENT
                btnSurvey.isEnabled = false
                btnLocate.isEnabled = false
                btnClearData.isEnabled = false
                btnSettings.isEnabled = false
                btnViewMatrix.isEnabled = false
                acquireScreenWakeLock()
                rebuildStrategyModels()
                experimentRunner.startExperiment(config)
            },
            onStopExperimentRequested = {
                experimentRunner.stopExperiment()
            },
            onShareCsv = { file ->
                try {
                    val authority = "${packageName}.fileprovider"
                    val contentUri = androidx.core.content.FileProvider.getUriForFile(this, authority, file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        putExtra(Intent.EXTRA_SUBJECT, "Positioning Experiment Results")
                        putExtra(Intent.EXTRA_TEXT, "Exported Wi-Fi positioning accuracy results across separations.")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Experiment Results"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )

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

        // Map Source Toggle: Local vs Imported Reference
        rgMapSource.setOnCheckedChangeListener { _, checkedId ->
            if (isScanningActive) {
                Toast.makeText(this, "Cannot change map source while scanning!", Toast.LENGTH_SHORT).show()
                if (activeMapSource == FingerprintSource.LOCAL) rbSourceLocal.isChecked = true
                else rbSourceImported.isChecked = true
                return@setOnCheckedChangeListener
            }
            if (checkedId == R.id.rbSourceImported) {
                applySourceMode(FingerprintSource.IMPORTED)
            } else {
                applySourceMode(FingerprintSource.LOCAL)
            }
        }

        // Share Button: Android Share Sheet to send survey map to other phones
        btnShareMap.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }
            if (surveyDatabase.isEmpty()) {
                Toast.makeText(this, "Local survey database is empty! Record spots first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val shareIntent = ShareManager.createShareIntent(this, surveyDatabase)
                startActivity(Intent.createChooser(shareIntent, "Share Wi-Fi Radio Map"))
                log("📤 Opening Android Share Sheet for ${surveyDatabase.size} local spots...\n")
            } catch (e: Exception) {
                log("❌ Error sharing radio map: ${e.message}\n")
                Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Import Button: Open document picker to load a reference map from another phone
        btnImportMap.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }
            try {
                importFileLauncher.launch(arrayOf("text/*", "*/*"))
            } catch (e: Exception) {
                log("❌ Error opening file picker: ${e.message}\n")
            }
        }

        // Calibrate Button: One-spot antenna offset calibration against imported reference map
        btnCalibrate.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }
            showCalibrationDialog()
        }

        // Stop Survey Button: Stops current survey session immediately and persists data collected so far
        btnStopSurvey.setOnClickListener {
            if (!isScanningActive || currentScanMode != ScanMode.SURVEY) return@setOnClickListener

            val spot = editSpotName.text.toString().trim()
            log("\n⏹️ User clicked STOP! Stopping survey for '$spot' at $currentSampleCount scans...\n")
            userRequestedStop = true
            scanHandler.removeCallbacksAndMessages(null)

            if (sampleBuffer.isEmpty() || currentSampleCount == 0) {
                log("⚠️ Survey stopped before any samples were captured.\n")
                isScanningActive = false
                btnSurvey.isEnabled = true
                btnLocate.isEnabled = true
                btnClearData.isEnabled = true
                btnSettings.isEnabled = true
                btnViewMatrix.isEnabled = true
                btnStopSurvey.visibility = View.GONE
                userRequestedStop = false
                releaseScreenWakeLock()
                return@setOnClickListener
            }

            activeTargetSamples = currentSampleCount
            processAndSaveAveragedFingerprint()
        }

        // 1. Survey Button: Record a labelled spot (or continue from existing survey if spot already exists)
        btnSurvey.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }

            val spotInput = editSpotName.text.toString().trim()
            if (spotInput.isEmpty()) {
                log("\n⚠️ Please enter a Spot Name (e.g. 'Spot 1') first!\n")
                return@setOnClickListener
            }

            val existingSpot = surveyDatabase.find { it.spotLabel.equals(spotInput, ignoreCase = true) }
            val spot = existingSpot?.spotLabel ?: spotInput

            userRequestedStop = false
            isScanningActive = true
            btnSurvey.isEnabled = false
            btnLocate.isEnabled = false
            btnClearData.isEnabled = false
            btnSettings.isEnabled = false
            btnViewMatrix.isEnabled = false
            btnStopSurvey.visibility = View.VISIBLE
            btnStopSurvey.isEnabled = true

            currentScanMode = ScanMode.SURVEY
            acquireScreenWakeLock()
            sampleBuffer.clear()
            bssidToSsidMap.clear()
            scanSnapshots.clear()

            if (existingSpot != null) {
                // Populate previous samples to continue seamlessly from old surveying
                for (m in existingSpot.measurements) {
                    bssidToSsidMap[m.bssid] = m.ssid
                    sampleBuffer[m.bssid] = m.rawReadings.toMutableList()
                }

                val priorScans = existingSpot.totalScans.coerceAtLeast(
                    existingSpot.measurements.maxOfOrNull { it.rawReadings.size } ?: 0
                )

                // Reconstruct previous scan snapshots so train/test split covers the entire series
                for (i in 0 until priorScans) {
                    val snapshot = mutableMapOf<String, Int>()
                    for (m in existingSpot.measurements) {
                        if (i < m.rawReadings.size) {
                            snapshot[m.bssid] = m.rawReadings[i]
                        }
                    }
                    scanSnapshots.add(snapshot)
                }

                currentSampleCount = priorScans
                activeTargetSamples = if (priorScans < surveySampleCount) {
                    surveySampleCount
                } else {
                    priorScans + surveySampleCount
                }

                updateProgressDisplay(currentSampleCount, activeTargetSamples, "Continuing '$spot'...")

                log("\n========================================\n")
                log("🔄 Continuing survey for existing spot '$spot'\n")
                log("• Prior Scans: $priorScans\n")
                log("• Target Total: $activeTargetSamples scans (+${activeTargetSamples - priorScans} more)\n")
                log("========================================\n")
            } else {
                currentSampleCount = 0
                activeTargetSamples = surveySampleCount

                updateProgressDisplay(0, activeTargetSamples, "Surveying '$spot'...")

                log("\n========================================\n")
                log("Survey started for new spot '$spot' ($activeTargetSamples iterations)...\n")
                log("========================================\n")
            }

            triggerWifiScan()
        }

        // 2. Locate Button: Measure live signals and run localization strategy
        btnLocate.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }

            val activeSpots = getActiveSurveyDatabase()
            if (activeSpots.isEmpty()) {
                val msg = if (activeMapSource == FingerprintSource.IMPORTED) {
                    "No imported reference spots found! Please import a reference map first."
                } else {
                    "No spots saved! Survey at least one spot first."
                }
                log("\n⚠️ $msg\n")
                return@setOnClickListener
            }

            btnStopSurvey.visibility = View.GONE

            activeTargetSamples = locateSampleCount
            isScanningActive = true
            btnSurvey.isEnabled = false
            btnLocate.isEnabled = false
            btnClearData.isEnabled = false
            btnSettings.isEnabled = false
            btnViewMatrix.isEnabled = false

            currentScanMode = ScanMode.LOCATE
            sampleBuffer.clear()
            bssidToSsidMap.clear()
            scanSnapshots.clear()
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
                    if (::experimentController.isInitialized) {
                        experimentController.refreshSpotList()
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
