package com.example.wififingerprint.experiment

import android.os.Handler
import android.os.Looper
import com.example.wififingerprint.strategy.FuzzyMatrixStrategy
import com.example.wififingerprint.strategy.KnnStrategy
import com.example.wififingerprint.strategy.LiveQueryScan
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface ExperimentListener {
    fun onScanProgress(trialIndex: Int, totalTrials: Int, scanIndex: Int, totalScans: Int)
    fun onTrialCompleted(result: ExperimentIterationResult, stats: ExperimentSummaryStats)
    fun onExperimentFinished(stats: ExperimentSummaryStats)
    fun onExperimentAborted(completedTrials: Int)
    fun onLog(message: String)
    fun requestWifiScan(): Boolean
}

class ExperimentRunner(
    private val knnStrategy: KnnStrategy,
    private val fuzzyMatrixStrategy: FuzzyMatrixStrategy,
    private val csvManager: ExperimentCsvManager,
    private val listener: ExperimentListener,
    private val queryPreprocessor: ((LiveQueryScan) -> LiveQueryScan)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private var config: ExperimentConfig = ExperimentConfig()

    var isRunning: Boolean = false
        private set

    private var currentTrialIndex = 0
    private var currentScanIndex = 0
    private val trialSampleBuffer = mutableMapOf<String, MutableList<Int>>()
    private val trialBssidToSsid = mutableMapOf<String, String>()
    private val sessionResults = mutableListOf<ExperimentIterationResult>()

    /**
     * Starts a batch experiment run with the specified configuration.
     */
    fun startExperiment(experimentConfig: ExperimentConfig) {
        if (isRunning) return
        this.config = experimentConfig
        this.isRunning = true
        this.currentTrialIndex = 1
        this.currentScanIndex = 0
        this.trialSampleBuffer.clear()
        this.trialBssidToSsid.clear()
        this.sessionResults.clear()

        listener.onLog("\n========================================\n")
        listener.onLog("🔬 Starting Experiment Run at '${config.actualSpot}' (${config.separationMeters}m separation)\n")
        listener.onLog("• Target Trials: ${config.totalTrials} (averaging ${config.locateScansPerTrial} scans each)\n")
        listener.onLog("• Ground Truth: (${config.actualX}m, ${config.actualY}m)\n")
        listener.onLog("========================================\n\n")

        triggerNextScan()
    }

    /**
     * Halts an active experiment run.
     */
    fun stopExperiment() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        val completed = sessionResults.size
        listener.onLog("\n⏹️ Experiment aborted by user after $completed trial(s).\n")
        listener.onExperimentAborted(completed)
    }

    /**
     * Called by MainActivity when a fresh Wi-Fi scan finishes.
     */
    fun onScanResultsAvailable(
        scanMap: Map<String, Int>,
        bssidToSsid: Map<String, String>
    ) {
        if (!isRunning) return

        currentScanIndex++
        trialBssidToSsid.putAll(bssidToSsid)

        for ((bssid, rssi) in scanMap) {
            trialSampleBuffer.getOrPut(bssid) { mutableListOf() }.add(rssi)
        }

        listener.onScanProgress(
            trialIndex = currentTrialIndex,
            totalTrials = config.totalTrials,
            scanIndex = currentScanIndex,
            totalScans = config.locateScansPerTrial
        )

        if (currentScanIndex < config.locateScansPerTrial) {
            // Need more scans for current locate estimation
            handler.postDelayed({
                if (isRunning) triggerNextScan()
            }, 1200)
        } else {
            // Trial completed: evaluate localization
            processCompletedTrial()
        }
    }

    private fun triggerNextScan() {
        if (!isRunning) return
        val started = listener.requestWifiScan()
        if (!started) {
            // Retry after delay if Wi-Fi manager rejected startScan
            handler.postDelayed({
                if (isRunning) triggerNextScan()
            }, 1500)
        }
    }

    private fun processCompletedTrial() {
        val averagedFingerprint = trialSampleBuffer.mapValues { (_, readings) ->
            readings.average().roundToInt()
        }

        val rawQuery = LiveQueryScan(averagedFingerprint, trialBssidToSsid)
        val query = queryPreprocessor?.invoke(rawQuery) ?: rawQuery

        // Evaluate both strategies on the query vector (with antenna offset if calibrated)
        val knnRes = knnStrategy.infer(query)
        val fuzzyRes = fuzzyMatrixStrategy.infer(query)

        val knnSpot = knnRes.predictedSpot ?: "Unknown"
        val fuzzySpot = fuzzyRes.predictedSpot ?: "Unknown"

        // Compute 2D coordinate predictions and metric Euclidean errors
        val knnCoords = knnStrategy.estimateCoordinatesMetres(query)
            ?: KnnStrategy.getCoordinatesForSpot(knnSpot)
            ?: Pair(0.0, 0.0)

        val fuzzyCoords = fuzzyMatrixStrategy.getCoordinatesForSpot(fuzzySpot)
            ?: KnnStrategy.getCoordinatesForSpot(fuzzySpot)
            ?: Pair(0.0, 0.0)

        val dxKnn = config.actualX - knnCoords.first
        val dyKnn = config.actualY - knnCoords.second
        val knnError = sqrt(dxKnn * dxKnn + dyKnn * dyKnn)

        val dxFuzzy = config.actualX - fuzzyCoords.first
        val dyFuzzy = config.actualY - fuzzyCoords.second
        val fuzzyError = sqrt(dxFuzzy * dxFuzzy + dyFuzzy * dyFuzzy)

        val iterResult = ExperimentIterationResult.create(
            separationMeters = config.separationMeters,
            trialIndex = currentTrialIndex,
            totalTrials = config.totalTrials,
            locateScans = config.locateScansPerTrial,
            actualSpot = config.actualSpot,
            actualX = config.actualX,
            actualY = config.actualY,
            knnPredSpot = knnSpot,
            knnPredX = knnCoords.first,
            knnPredY = knnCoords.second,
            knnErrorMeters = knnError,
            fuzzyPredSpot = fuzzySpot,
            fuzzyPredX = fuzzyCoords.first,
            fuzzyPredY = fuzzyCoords.second,
            fuzzyErrorMeters = fuzzyError,
            rawSignalsMap = averagedFingerprint
        )

        csvManager.appendResult(iterResult)
        sessionResults.add(iterResult)

        val stats = computeRunningStats()
        listener.onTrialCompleted(iterResult, stats)

        val knnMatchIcon = if (iterResult.knnCorrect) "✅" else "❌"
        val fuzzyMatchIcon = if (iterResult.fuzzyCorrect) "✅" else "❌"

        listener.onLog("Trial ${currentTrialIndex}/${config.totalTrials}: " +
                "k-NN=$knnSpot $knnMatchIcon (${"%.2f".format(knnError)}m) | " +
                "Fuzzy=$fuzzySpot $fuzzyMatchIcon (${"%.2f".format(fuzzyError)}m)\n")

        if (currentTrialIndex < config.totalTrials) {
            currentTrialIndex++
            currentScanIndex = 0
            trialSampleBuffer.clear()
            trialBssidToSsid.clear()

            handler.postDelayed({
                if (isRunning) triggerNextScan()
            }, 1200)
        } else {
            isRunning = false
            listener.onLog("\n========================================\n")
            listener.onLog("🎉 Experiment Finished! (${sessionResults.size} trials)\n")
            listener.onLog("• k-NN Accuracy:   ${"%.1f".format(stats.knnAccuracyPct)}% (Mean Err: ${"%.2f".format(stats.knnAvgErrorMeters)}m)\n")
            listener.onLog("• Fuzzy Accuracy:  ${"%.1f".format(stats.fuzzyAccuracyPct)}% (Mean Err: ${"%.2f".format(stats.fuzzyAvgErrorMeters)}m)\n")
            listener.onLog("💾 Results appended to ${ExperimentCsvManager.EXPERIMENT_CSV_FILE_NAME}\n")
            listener.onLog("========================================\n\n")
            listener.onExperimentFinished(stats)
        }
    }

    private fun computeRunningStats(): ExperimentSummaryStats {
        val total = sessionResults.size
        if (total == 0) {
            return ExperimentSummaryStats(
                separationMeters = config.separationMeters,
                actualSpot = config.actualSpot,
                completedTrials = 0,
                targetTrials = config.totalTrials,
                knnCorrectCount = 0,
                knnAccuracyPct = 0.0,
                knnAvgErrorMeters = 0.0,
                fuzzyCorrectCount = 0,
                fuzzyAccuracyPct = 0.0,
                fuzzyAvgErrorMeters = 0.0
            )
        }

        val knnCorrect = sessionResults.count { it.knnCorrect }
        val fuzzyCorrect = sessionResults.count { it.fuzzyCorrect }
        val knnAvgErr = sessionResults.map { it.knnErrorMeters }.average()
        val fuzzyAvgErr = sessionResults.map { it.fuzzyErrorMeters }.average()

        return ExperimentSummaryStats(
            separationMeters = config.separationMeters,
            actualSpot = config.actualSpot,
            completedTrials = total,
            targetTrials = config.totalTrials,
            knnCorrectCount = knnCorrect,
            knnAccuracyPct = (knnCorrect.toDouble() / total) * 100.0,
            knnAvgErrorMeters = knnAvgErr,
            fuzzyCorrectCount = fuzzyCorrect,
            fuzzyAccuracyPct = (fuzzyCorrect.toDouble() / total) * 100.0,
            fuzzyAvgErrorMeters = fuzzyAvgErr
        )
    }
}

