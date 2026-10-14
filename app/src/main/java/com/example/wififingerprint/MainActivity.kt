package com.example.wififingerprint

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.net.wifi.WifiManager
import android.content.Context
import android.Manifest
import android.widget.ScrollView
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileWriter
import kotlin.math.pow
import kotlin.math.sqrt

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

    private lateinit var wifiManager: WifiManager
    private val surveyDatabase = mutableListOf<Fingerprint>()
    private val CSV_FILE_NAME = "wifi_fingerprints.csv"

    // Configurable parameters (persisted in SharedPreferences)
    private var surveySampleCount = 10     // Scans per survey spot
    private var locateSampleCount = 10     // Scans for locate
    private var knnK = 1                  // k for k-NN matching
    private var outlierFilterK = 6        // K-closest samples to keep
    private var activeTargetSamples = 10  // Current active target

    private var currentSampleCount = 0
    private var isSurveying = true // true = Survey mode, false = Locate mode
    private var isScanningActive = false // Only true when user explicitly pressed a button

    private val sampleBuffer = mutableMapOf<String, MutableList<Int>>()
    private val bssidToSsidMap = mutableMapOf<String, String>()
    private val scanHandler = Handler(Looper.getMainLooper())

    private fun loadSettings() {
        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        surveySampleCount = prefs.getInt("survey_count", 10)
        locateSampleCount = prefs.getInt("locate_count", 10)
        knnK = prefs.getInt("knn_k", 1)
        outlierFilterK = prefs.getInt("outlier_k", 6)
        updateSettingsSummary()
    }

    private fun saveSettings(survey: Int, locate: Int, knn: Int, outlier: Int) {
        surveySampleCount = survey
        locateSampleCount = locate
        knnK = knn
        outlierFilterK = outlier

        val prefs = getSharedPreferences("FingerprintSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("survey_count", survey)
            putInt("locate_count", locate)
            putInt("knn_k", knn)
            putInt("outlier_k", outlier)
            apply()
        }
        updateSettingsSummary()
        log("\n⚙️ Settings saved: Survey=$survey | Locate=$locate | k-NN=$knn | OutlierFilter=$outlier\n")
    }

    private fun updateSettingsSummary() {
        tvSettingsSummary.text = "Params: Survey=$surveySampleCount | Locate=$locateSampleCount | k-NN=$knnK | Filter=$outlierFilterK"
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val editSurvey = dialogView.findViewById<EditText>(R.id.editSurveyCount)
        val editLocate = dialogView.findViewById<EditText>(R.id.editLocateCount)
        val editKnn = dialogView.findViewById<EditText>(R.id.editKnnK)
        val editOutlier = dialogView.findViewById<EditText>(R.id.editOutlierK)

        editSurvey.setText(surveySampleCount.toString())
        editLocate.setText(locateSampleCount.toString())
        editKnn.setText(knnK.toString())
        editOutlier.setText(outlierFilterK.toString())

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Configure Parameters")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sCount = editSurvey.text.toString().toIntOrNull() ?: surveySampleCount
                val lCount = editLocate.text.toString().toIntOrNull() ?: locateSampleCount
                val kVal = editKnn.text.toString().toIntOrNull() ?: knnK
                val outVal = editOutlier.text.toString().toIntOrNull() ?: outlierFilterK

                if (sCount <= 0 || lCount <= 0 || kVal <= 0 || outVal <= 0) {
                    Toast.makeText(this, "Parameters must be positive integers!", Toast.LENGTH_SHORT).show()
                } else if (outVal > sCount) {
                    Toast.makeText(this, "Outlier filter ($outVal) cannot exceed survey count ($sCount)!", Toast.LENGTH_LONG).show()
                } else {
                    saveSettings(sCount, lCount, kVal, outVal)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Structure holding raw readings, filtered readings, and calculated average
    data class ApMeasurement(
        val bssid: String,
        val ssid: String,
        val rawReadings: List<Int>,
        val filteredReadings: List<Int>,
        val avgRssi: Int
    )

    // Outlier rejection: Calculates median, sorts by distance to median, keeps k closest points
    private fun filterKClosestPoints(readings: List<Int>, k: Int): List<Int> {
        if (readings.size <= k) return readings.sorted()

        val sorted = readings.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }

        // Sort by absolute distance from median, take top k closest, and return in sorted order
        return readings.sortedBy { kotlin.math.abs(it - median) }.take(k).sorted()
    }

    private fun processAndSaveAveragedFingerprint() {
        val processedMeasurements = mutableListOf<ApMeasurement>()
        val averagedFingerprint = mutableMapOf<String, Int>()
        val omittedTransientAps = mutableListOf<String>()

        for ((bssid, rawReadings) in sampleBuffer) {
            val ssid = bssidToSsidMap[bssid] ?: "Unknown"

            // In Locate Mode: Omit AP if it did NOT appear at least K times (< outlierFilterK)
            if (!isSurveying && rawReadings.size < outlierFilterK) {
                omittedTransientAps.add("$ssid ($bssid) [seen ${rawReadings.size}/$activeTargetSamples times]")
                continue
            }

            // Exclude outliers: pick k-closest points to median
            val filteredReadings = filterKClosestPoints(rawReadings, outlierFilterK)
            val cleanAvg = filteredReadings.average().roundToInt()

            averagedFingerprint[bssid] = cleanAvg
            processedMeasurements.add(ApMeasurement(bssid, ssid, rawReadings, filteredReadings, cleanAvg))
        }

        if (isSurveying) {
            val spot = editSpotName.text.toString().trim()
            log("\n--- Averaged Fingerprint for $spot (Outlier Filtered: $outlierFilterK of $activeTargetSamples) ---\n")
            for (m in processedMeasurements) {
                log("${m.ssid} (${m.bssid})\n")
                log("  Raw (${m.rawReadings.size}): ${m.rawReadings}\n")
                log("  Filtered ($outlierFilterK-closest): ${m.filteredReadings} -> Clean Avg: ${m.avgRssi} dBm\n")
            }

            // Save to memory
            surveyDatabase.add(Fingerprint(spot, averagedFingerprint))

            // Save to CSV file with raw, filtered, and averaged values
            saveFingerprintToCsv(spot, processedMeasurements)

            log("\n💾 Saved '$spot' with ${averagedFingerprint.size} averaged APs to database & CSV.\n")
            log("Total spots in memory: ${surveyDatabase.size}\n")
        } else {
            // Locate Mode: Log valid vs omitted transient APs, then run k-NN on available dimensions
            log("\n--- Live Scan (Outlier Filtered: $outlierFilterK of $activeTargetSamples) ---\n")
            if (omittedTransientAps.isNotEmpty()) {
                log("⚠️ Omitted ${omittedTransientAps.size} transient APs (seen < $outlierFilterK times):\n")
                for (omitted in omittedTransientAps) {
                    log("   🚫 $omitted\n")
                }
            }

            for (m in processedMeasurements) {
                log("${m.ssid} (${m.bssid})\n")
                log("  Raw (${m.rawReadings.size}): ${m.rawReadings}\n")
                log("  Filtered ($outlierFilterK-closest): ${m.filteredReadings} -> Clean Avg: ${m.avgRssi} dBm\n")
            }

            locateSpotUsingKnn(averagedFingerprint, k = knnK)
        }

        // FINISHED: Unlock scanning state and re-enable buttons
        isScanningActive = false
        btnSurvey.isEnabled = true
        btnLocate.isEnabled = true
        btnClearData.isEnabled = true
        btnSettings.isEnabled = true
    }

    // k-Nearest Neighbors (k-NN) matching ONLY available dimensions
    private fun locateSpotUsingKnn(liveScan: Map<String, Int>, k: Int = 1) {
        if (surveyDatabase.isEmpty()) {
            log("\n⚠️ No survey spots in database to compare against!\n")
            return
        }

        log("\n========================================\n")
        log("🔍 Running $k-NN Localization (Available Dimensions Only)...\n")
        log("========================================\n")

        // Stores Triple(SpotLabel, RMSE_Distance, MatchedDimensionsCount)
        val distances = mutableListOf<Triple<String, Double, Int>>()

        for (fp in surveyDatabase) {
            // Match ONLY the dimensions that are available in both live scan and this surveyed spot
            val commonBssids = liveScan.keys.intersect(fp.readings.keys)

            if (commonBssids.isEmpty()) {
                // No common APs found: cannot match this spot
                distances.add(Triple(fp.spotLabel, Double.MAX_VALUE, 0))
            } else {
                var sumSquare = 0.0
                for (bssid in commonBssids) {
                    val rSurvey = fp.readings[bssid]!!
                    val rLive = liveScan[bssid]!!
                    sumSquare += (rSurvey - rLive).toDouble().pow(2)
                }

                // Root Mean Square Error (RMSE) across only available matching AP dimensions
                val rmse = sqrt(sumSquare / commonBssids.size)
                distances.add(Triple(fp.spotLabel, rmse, commonBssids.size))
            }
        }

        // Sort by distance ascending (closest match first). If distances match, tie-break by higher matched AP count.
        distances.sortWith(compareBy({ it.second }, { -it.third }))

        log("Distance Rankings (RMSE over available dimensions):\n")
        for ((index, item) in distances.withIndex()) {
            val marker = if (index < k) "👉 [TOP-$k]" else "   "
            if (item.second == Double.MAX_VALUE) {
                log("$marker ${item.first} -> No common APs matched!\n")
            } else {
                log("$marker ${item.first} -> Avg Diff: ${"%.2f".format(item.second)} dBm (matched ${item.third} APs)\n")
            }
        }

        val validMatches = distances.filter { it.second != Double.MAX_VALUE }
        if (validMatches.isNotEmpty()) {
            val topK = validMatches.take(k)
            if (k > 1) {
                log("\nTop-$k Neighbors for Voting:\n")
                for ((idx, cand) in topK.withIndex()) {
                    log("   ${idx + 1}. ${cand.first} (RMSE: ${"%.2f".format(cand.second)} dBm, ${cand.third} APs)\n")
                }
            }

            // Majority voting among top k candidates
            val votes = topK.groupingBy { it.first }.eachCount()
            val winner = votes.maxByOrNull { it.value }?.key ?: topK.first().first
            val winnerCand = topK.first { it.first == winner }

            log("\n📍 PREDICTED LOCATION ($k-NN Mode): $winner (Avg Diff: ${"%.2f".format(winnerCand.second)} dBm, ${winnerCand.third} APs matched)\n")
        } else {
            log("\n⚠️ Could not determine location: no common APs matched with any saved spot.\n")
        }
    }

    // Save fingerprint entry into wifi_fingerprints.csv including raw & filtered samples
    private fun saveFingerprintToCsv(spot: String, measurements: List<ApMeasurement>) {
        try {
            val file = File(getExternalFilesDir(null), CSV_FILE_NAME)
            val isNewFile = !file.exists()
            val writer = FileWriter(file, true) // append mode
            val timestamp = System.currentTimeMillis()

            if (isNewFile) {
                writer.append("timestamp,spot_label,bssid,ssid,raw_samples,filtered_samples,avg_rssi\n")
            }

            for (m in measurements) {
                val cleanSsid = m.ssid.replace(",", " ")
                val rawStr = "\"[" + m.rawReadings.joinToString(";") + "]\""
                val filteredStr = "\"[" + m.filteredReadings.joinToString(";") + "]\""
                writer.append("$timestamp,$spot,${m.bssid},$cleanSsid,$rawStr,$filteredStr,${m.avgRssi}\n")
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
                return
            }

            val scanResults = wifiManager.scanResults
            currentSampleCount++

            log("Sample $currentSampleCount of $activeTargetSamples collected (${scanResults.size} APs visible).\n")

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
        log("Starting Wi-Fi scan...\n")
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

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        log("App initialized.\n")
        checkAndRequestPermissions()
        loadSettings() // Load user-configured parameters from SharedPreferences
        loadFingerprintsFromCsv() // Load saved spots from CSV

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
            sampleBuffer.clear()
            bssidToSsidMap.clear()
            currentSampleCount = 0

            log("\n========================================\n")
            log("Collecting $surveySampleCount samples for '$spot'...\n")
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
        unregisterReceiver(wifiScanReceiver)
    }
    private fun log(message: String) {
        tvLogs.append(message)
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}