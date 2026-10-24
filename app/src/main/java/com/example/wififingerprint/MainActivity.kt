package com.example.wififingerprint

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.net.wifi.WifiManager
import android.content.Context
import android.Manifest
import android.widget.ScrollView
import android.widget.ProgressBar
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.wifi.ScanResult
import android.content.IntentFilter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileWriter
import kotlin.math.pow
import kotlin.math.sqrt

import android.view.WindowManager
import android.os.PowerManager
import android.view.View
import android.widget.Toast

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
    private lateinit var tvSettingsSummary: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvProgressText: TextView

    private lateinit var wifiManager: WifiManager
    private val surveyDatabase = mutableListOf<Fingerprint>()
    private val CSV_FILE_NAME = "wifi_fingerprints.csv"

    // Dynamic WakeLock to keep screen on ONLY while surveying
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireScreenWakeLock() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            findViewById<View>(android.R.id.content)?.keepScreenOn = true

            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WifiFingerprint:SurveyWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L) // 60 minutes safety timeout
            }
        } catch (e: Exception) {
            // Ignore if restricted
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
    private var surveySampleCount = 20    // Scans per survey spot (can be set large, e.g. 50, 100)
    private var locateSampleCount = 10    // Scans for locate
    private var knnK = 1                 // k for k-NN matching
    private var activeTargetSamples = 20 // Current active target

    private var currentSampleCount = 0
    private var isSurveying = true // true = Survey mode, false = Locate mode
    private var isScanningActive = false // Only true when user explicitly pressed a button

    private val sampleBuffer = mutableMapOf<String, MutableList<Int>>()
    private val bssidToSsidMap = mutableMapOf<String, String>()
    private val scanHandler = Handler(Looper.getMainLooper())

    private fun loadSettings() {
        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        surveySampleCount = prefs.getInt("survey_count", 20)
        locateSampleCount = prefs.getInt("locate_count", 10)
        knnK = prefs.getInt("knn_k", 1)
        updateSettingsSummary()
    }

    private fun saveSettings(survey: Int, locate: Int, knn: Int) {
        surveySampleCount = survey
        locateSampleCount = locate
        knnK = knn

        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("survey_count", survey)
            putInt("locate_count", locate)
            putInt("knn_k", knn)
            apply()
        }
        updateSettingsSummary()
        if (!isScanningActive) {
            updateProgressDisplay(0, surveySampleCount, "Survey Progress")
        }
        log("\n⚙️ Settings saved: Survey=$survey | Locate=$locate | k-NN=$knn\n")
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
        tvSettingsSummary.text = "Params: Survey=$surveySampleCount | Locate=$locateSampleCount | k-NN=$knnK"
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val editSurvey = dialogView.findViewById<EditText>(R.id.editSurveyCount)
        val editLocate = dialogView.findViewById<EditText>(R.id.editLocateCount)
        val editKnn = dialogView.findViewById<EditText>(R.id.editKnnK)

        editSurvey.setText(surveySampleCount.toString())
        editLocate.setText(locateSampleCount.toString())
        editKnn.setText(knnK.toString())

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Configure Parameters")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sCount = editSurvey.text.toString().toIntOrNull() ?: surveySampleCount
                val lCount = editLocate.text.toString().toIntOrNull() ?: locateSampleCount
                val kVal = editKnn.text.toString().toIntOrNull() ?: knnK

                if (sCount <= 0 || lCount <= 0 || kVal <= 0) {
                    Toast.makeText(this, "Parameters must be positive integers!", Toast.LENGTH_SHORT).show()
                } else {
                    saveSettings(sCount, lCount, kVal)
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
            // Compute mean RSSI directly from all collected raw readings
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

            // Save to memory
            surveyDatabase.add(Fingerprint(spot, averagedFingerprint))

            // Save to CSV file with all raw samples
            saveFingerprintToCsv(spot, processedMeasurements)

            log("💾 Saved '$spot' with ${averagedFingerprint.size} APs to database & CSV.\n")
            log("Total spots in memory: ${surveyDatabase.size}\n")
        } else {
            updateProgressDisplay(activeTargetSamples, activeTargetSamples, "Locate Completed")
            // Locate Mode: Log live readings and run k-NN on available dimensions
            log("\n--- Live Scan ($activeTargetSamples iterations) ---\n")
            for (m in processedMeasurements) {
                log("${m.ssid} (${m.bssid})\n")
                log("  Readings (${m.rawReadings.size}): ${m.rawReadings} -> Avg: ${m.avgRssi} dBm\n")
            }

            locateSpotUsingKnn(averagedFingerprint, k = knnK)
        }

        // FINISHED: Unlock scanning state and re-enable buttons
        isScanningActive = false
        btnSurvey.isEnabled = true
        btnLocate.isEnabled = true
        btnClearData.isEnabled = true
        btnSettings.isEnabled = true
        releaseScreenWakeLock()
    }

    // Candidate match holding penalized distance, raw mutual RMSE, and AP coverage
    private data class CandidateMatch(
        val spotLabel: String,
        val totalDistance: Double,
        val rawRmse: Double,
        val matchedAps: Int,
        val totalAps: Int
    )

    // k-Nearest Neighbors (k-NN) matching with Mutual RMSE + Overlap Penalty (Fix for Bug 1)
    private fun locateSpotUsingKnn(liveScan: Map<String, Int>, k: Int = 1) {
        if (surveyDatabase.isEmpty()) {
            log("\n⚠️ No survey spots in database to compare against!\n")
            return
        }

        log("\n========================================\n")
        log("🔍 Running $k-NN Localization (Penalized Mutual RMSE)...\n")
        log("========================================\n")

        val distances = mutableListOf<CandidateMatch>()
        val penaltyAlpha = 15.0 // dBm scaling penalty for non-overlapping/missing APs

        for (fp in surveyDatabase) {
            val commonBssids = liveScan.keys.intersect(fp.readings.keys)
            val maxAps = maxOf(liveScan.size, fp.readings.size)

            if (commonBssids.isEmpty()) {
                // No common APs found: cannot match this spot
                distances.add(CandidateMatch(fp.spotLabel, Double.MAX_VALUE, Double.MAX_VALUE, 0, maxAps))
            } else {
                var sumSquare = 0.0
                for (bssid in commonBssids) {
                    val rSurvey = fp.readings[bssid]!!
                    val rLive = liveScan[bssid]!!
                    sumSquare += (rSurvey - rLive).toDouble().pow(2)
                }

                // Raw Root Mean Square Error (RMSE) across only common APs
                val rawRmse = sqrt(sumSquare / commonBssids.size)

                // Overlap ratio: proportion of APs that are mutually shared
                val overlapRatio = commonBssids.size.toDouble() / maxAps.toDouble()

                // Penalized Distance = Raw RMSE + penalty proportional to missing AP dimensions
                // Eliminates false matches where a distant spot shares only 1 AP by coincidence
                val penalizedDistance = rawRmse + penaltyAlpha * (1.0 - overlapRatio)

                distances.add(CandidateMatch(fp.spotLabel, penalizedDistance, rawRmse, commonBssids.size, maxAps))
            }
        }

        // Sort by penalized distance ascending (closest match first).
        // Tie-break by higher matched AP count, then by lower raw RMSE.
        distances.sortWith(compareBy({ it.totalDistance }, { -it.matchedAps }, { it.rawRmse }))

        log("Distance Rankings (Mutual RMSE + Missing AP Overlap Penalty):\n")
        for ((index, item) in distances.withIndex()) {
            val marker = if (index < k) "👉 [TOP-$k]" else "   "
            if (item.totalDistance == Double.MAX_VALUE) {
                log("$marker ${item.spotLabel} -> No common APs matched!\n")
            } else {
                log("$marker ${item.spotLabel} -> Score: ${"%.2f".format(item.totalDistance)} dBm (RMSE: ${"%.2f".format(item.rawRmse)} dBm, matched ${item.matchedAps}/${item.totalAps} APs)\n")
            }
        }

        val validMatches = distances.filter { it.totalDistance != Double.MAX_VALUE }
        if (validMatches.isNotEmpty()) {
            val topK = validMatches.take(k)
            if (k > 1) {
                log("\nTop-$k Neighbors for Voting:\n")
                for ((idx, cand) in topK.withIndex()) {
                    log("   ${idx + 1}. ${cand.spotLabel} (Score: ${"%.2f".format(cand.totalDistance)} dBm, RMSE: ${"%.2f".format(cand.rawRmse)} dBm, ${cand.matchedAps}/${cand.totalAps} APs)\n")
                }
            }

            // Distance-Weighted k-NN (WKNN) Voting (Fix for Bug 2):
            // Instead of giving equal 1-vote to every neighbor, weight each candidate inversely by distance:
            // weight = 1.0 / (distance + 0.1). Closer neighbors have much higher voting power.
            val spotWeights = mutableMapOf<String, Double>()
            for (cand in topK) {
                val weight = 1.0 / (cand.totalDistance + 0.1)
                spotWeights[cand.spotLabel] = (spotWeights[cand.spotLabel] ?: 0.0) + weight
            }

            val totalWeightSum = spotWeights.values.sum()
            val winner = spotWeights.maxByOrNull { it.value }?.key ?: topK.first().spotLabel
            val winnerCand = topK.first { it.spotLabel == winner }
            val confidence = if (totalWeightSum > 0) ((spotWeights[winner]!! / totalWeightSum) * 100.0).roundToInt() else 100

            if (k > 1) {
                log("\nWeighted k-NN Voting Weights:\n")
                for ((spot, weight) in spotWeights.toList().sortedByDescending { it.second }) {
                    val pct = if (totalWeightSum > 0) ((weight / totalWeightSum) * 100.0).roundToInt() else 0
                    log("   • $spot: weight ${"%.3f".format(weight)} ($pct%)\n")
                }
            }

            log("\n📍 PREDICTED LOCATION (Weighted $k-NN): $winner (Confidence: $confidence%, Score: ${"%.2f".format(winnerCand.totalDistance)} dBm, RMSE: ${"%.2f".format(winnerCand.rawRmse)} dBm, ${winnerCand.matchedAps}/${winnerCand.totalAps} APs matched)\n")
        } else {
            log("\n⚠️ Could not determine location: no common APs matched with any saved spot.\n")
        }
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

    // Load previously saved survey data from wifi_fingerprints.csv on startup (supports both old 5-column and new 7-column formats)
    private fun loadFingerprintsFromCsv() {
        try {
            val file = File(getExternalFilesDir(null), CSV_FILE_NAME)
            if (!file.exists()) return

            val loadedSpots = mutableMapOf<String, MutableMap<String, Int>>()

            file.forEachLine { line ->
                if (!line.startsWith("timestamp") && line.isNotBlank()) {
                    val parts = line.split(",")
                    if (parts.size >= 5) {
                        val spot = parts[1].trim()
                        val bssid = parts[2].trim()
                        // Use parts.last() so it works for both 5-column and 7-column CSVs
                        val rssi = parts.last().trim().toIntOrNull() ?: -100
                        loadedSpots.getOrPut(spot) { mutableMapOf() }[bssid] = rssi
                    }
                }
            }

            surveyDatabase.clear()
            for ((spot, map) in loadedSpots) {
                surveyDatabase.add(Fingerprint(spot, map))
            }

            log("📂 Loaded ${surveyDatabase.size} saved spot(s) from ${file.name}\n")
        } catch (e: Exception) {
            log("❌ Error reading CSV: ${e.message}\n")
        }
    }
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // CRITICAL: Ignore automatic background scans triggered by Android OS!
            if (!isScanningActive) return

            // CRITICAL: Reject stale/cached scan results. Verify Android actually completed a fresh scan!
            val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (!updated) {
                // Android did not perform a new scan (throttled/cached results).
                // Do NOT increment sample count or record duplicates! Schedule a retry.
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

            // Update horizontal progress bar (currentiteration/totaliteration) percentile completed
            val label = if (isSurveying) "Surveying '${editSpotName.text.toString().trim()}'..." else "Locating..."
            updateProgressDisplay(currentSampleCount, activeTargetSamples, label)

            // When surveying, suppress intermediate scan logging to avoid cluttering the UI
            if (!isSurveying) {
                log("Sample $currentSampleCount of $activeTargetSamples collected (${scanResults.size} APs visible).\n")
            }

            // Accumulate RSSI for each BSSID seen in this scan
            for (ap in scanResults) {
                val bssid = ap.BSSID
                val rssi = ap.level
                val ssid = if (ap.SSID.isNullOrEmpty()) "<Hidden>" else ap.SSID

                bssidToSsidMap[bssid] = ssid

                // If BSSID not in buffer yet, create empty list, then add rssi
                sampleBuffer.getOrPut(bssid) { mutableListOf() }.add(rssi)
            }

            // Check if we still need more samples
            if (currentSampleCount < activeTargetSamples) {
                // Wait 1.2 seconds before the next scan to let the Wi-Fi radio refresh
                scanHandler.postDelayed({
                    triggerWifiScan()
                }, 1200)
            } else {
                // ALL SAMPLES COLLECTED -> COMPUTE AVERAGES!
                processAndSaveAveragedFingerprint()
            }
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
            releaseScreenWakeLock()
            return
        }

        if (!isSurveying) {
            log("Starting Wi-Fi scan...\n")
        }
        val started = wifiManager.startScan()
        if (!started) {
            log("⚠️ Scan throttled by Android OS (Retrying in 2s)...\n")
            scanHandler.postDelayed({
                if (isScanningActive) {
                    val retry = wifiManager.startScan()
                    if (!retry && currentSampleCount == 0) {
                        log("❌ Scan blocked. Ensure Wi-Fi scan throttling is OFF in Developer Options.\n")
                        isScanningActive = false
                        btnSurvey.isEnabled = true
                        btnLocate.isEnabled = true
                        btnClearData.isEnabled = true
                        btnSettings.isEnabled = true
                        releaseScreenWakeLock()
                    }
                }
            }, 2000)
        }
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Android 13+ requires NEARBY_WIFI_DEVICES permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Filter out permissions already granted
        val permissionsToRequest = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            log("Requesting permissions...\n")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
        } else {
            log("All permissions already granted!\n")
        }
     //if(ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED)
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
        tvSettingsSummary = findViewById(R.id.tvSettingsSummary)
        progressBar = findViewById(R.id.progressBar)
        tvProgressLabel = findViewById(R.id.tvProgressLabel)
        tvProgressText = findViewById(R.id.tvProgressText)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        log("App initialized.\n")
        checkAndRequestPermissions()
        loadSettings() // Load user-configured parameters from SharedPreferences
        loadFingerprintsFromCsv() // Load saved spots from CSV
        updateProgressDisplay(0, surveySampleCount, "Survey Progress")

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        // 0. Settings Button: Configure survey/locate counts, k-NN k, and outlier filter
        btnSettings.setOnClickListener {
            if (isScanningActive) {
                log("\n⚠️ A scan is already in progress, please wait...\n")
                return@setOnClickListener
            }
            showSettingsDialog()
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

        // 2. Locate Button: Measure live signals and run k-NN to find where you are
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

            isSurveying = false
            sampleBuffer.clear()
            bssidToSsidMap.clear()
            currentSampleCount = 0

            updateProgressDisplay(0, activeTargetSamples, "Locating position...")

            log("\n========================================\n")
            log("📍 Locating position (averaging $locateSampleCount scans, $knnK-NN)...\n")
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