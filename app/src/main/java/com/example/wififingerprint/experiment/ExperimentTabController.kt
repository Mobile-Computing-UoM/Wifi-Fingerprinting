package com.example.wififingerprint.experiment

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.wififingerprint.R
import com.example.wififingerprint.strategy.RawSurveySpot
import java.io.File
import java.util.Locale

class ExperimentTabController(
    private val activity: Activity,
    private val rootView: View,
    private val csvManager: ExperimentCsvManager,
    private val getAvailableSpots: () -> List<RawSurveySpot>,
    private val onStartExperimentRequested: (ExperimentConfig) -> Unit,
    private val onStopExperimentRequested: () -> Unit,
    private val onShareCsv: (File) -> Unit
) {
    // Views
    private val tvExpHeaderStats: TextView = rootView.findViewById(R.id.tvExpHeaderStats)
    private val btnExpExportCsv: Button = rootView.findViewById(R.id.btnExpExportCsv)
    private val btnExpClearCsv: Button = rootView.findViewById(R.id.btnExpClearCsv)

    private val spinnerExpSeparation: Spinner = rootView.findViewById(R.id.spinnerExpSeparation)
    private val spinnerExpSpot: Spinner = rootView.findViewById(R.id.spinnerExpSpot)
    private val editExpCoordX: EditText = rootView.findViewById(R.id.editExpCoordX)
    private val editExpCoordY: EditText = rootView.findViewById(R.id.editExpCoordY)
    private val editExpTrials: EditText = rootView.findViewById(R.id.editExpTrials)
    private val editExpScans: EditText = rootView.findViewById(R.id.editExpScans)

    private val btnExpStart: Button = rootView.findViewById(R.id.btnExpStart)
    private val btnExpStop: Button = rootView.findViewById(R.id.btnExpStop)

    private val layoutExpProgress: LinearLayout = rootView.findViewById(R.id.layoutExpProgress)
    private val tvExpProgressTrial: TextView = rootView.findViewById(R.id.tvExpProgressTrial)
    private val tvExpProgressScan: TextView = rootView.findViewById(R.id.tvExpProgressScan)
    private val pbExpScan: ProgressBar = rootView.findViewById(R.id.pbExpScan)

    private val tvExpKnnStats: TextView = rootView.findViewById(R.id.tvExpKnnStats)
    private val tvExpFuzzyStats: TextView = rootView.findViewById(R.id.tvExpFuzzyStats)
    private val tvExpPatternStats: TextView = rootView.findViewById(R.id.tvExpPatternStats)
    private val tvExpMamdaniStats: TextView = rootView.findViewById(R.id.tvExpMamdaniStats)

    private val tvExpEmptyHistory: TextView = rootView.findViewById(R.id.tvExpEmptyHistory)
    private val rvExpHistory: RecyclerView = rootView.findViewById(R.id.rvExpHistory)

    private var adapter: ExperimentAdapter? = null
    private val separationOptions = listOf("2.0m", "3.0m", "4.0m", "5.0m", "10.0m")

    init {
        setupSeparationSpinner()
        setupSpotSpinner()
        setupButtons()
        setupRecyclerView()
        refreshHistory()
    }

    private fun setupSeparationSpinner() {
        val sepAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, separationOptions)
        sepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExpSeparation.adapter = sepAdapter
    }

    fun refreshSpotList() {
        val spots = getAvailableSpots()
        val spotLabels = if (spots.isEmpty()) {
            listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8")
        } else {
            spots.map { it.spotLabel }
        }

        val spotAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, spotLabels)
        spotAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExpSpot.adapter = spotAdapter

        spinnerExpSpot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = spotLabels.getOrNull(position) ?: return
                val matchedSpot = spots.find { it.spotLabel.equals(selected, ignoreCase = true) }
                if (matchedSpot != null && (matchedSpot.coordX != 0.0 || matchedSpot.coordY != 0.0)) {
                    editExpCoordX.setText(String.format(Locale.US, "%.2f", matchedSpot.coordX))
                    editExpCoordY.setText(String.format(Locale.US, "%.2f", matchedSpot.coordY))
                } else {
                    // Quick default if S1-S8 naming is used
                    val match = Regex("S(\\d+)", RegexOption.IGNORE_CASE).find(selected)
                    if (match != null) {
                        val num = match.groupValues[1].toIntOrNull() ?: 1
                        val sep = getSelectedSeparationMeters()
                        val defaultX = (num - 1) * sep
                        editExpCoordX.setText(String.format(Locale.US, "%.2f", defaultX))
                        editExpCoordY.setText("0.0")
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSpotSpinner() {
        refreshSpotList()
    }

    private fun getSelectedSeparationMeters(): Double {
        val selected = spinnerExpSeparation.selectedItem?.toString() ?: "2.0m"
        return selected.removeSuffix("m").toDoubleOrNull() ?: 2.0
    }

    private fun setupButtons() {
        btnExpStart.setOnClickListener {
            val spot = spinnerExpSpot.selectedItem?.toString()?.trim() ?: "S1"
            val sep = getSelectedSeparationMeters()
            val coordX = editExpCoordX.text.toString().toDoubleOrNull() ?: 0.0
            val coordY = editExpCoordY.text.toString().toDoubleOrNull() ?: 0.0
            val trials = editExpTrials.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 20
            val scans = editExpScans.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10

            val config = ExperimentConfig(
                separationMeters = sep,
                actualSpot = spot,
                actualX = coordX,
                actualY = coordY,
                totalTrials = trials,
                locateScansPerTrial = scans
            )

            setRunningState(true)
            onStartExperimentRequested(config)
        }

        btnExpStop.setOnClickListener {
            onStopExperimentRequested()
        }

        btnExpExportCsv.setOnClickListener {
            val file = csvManager.getFile()
            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(activity, "No experiment data to export!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onShareCsv(file)
        }

        btnExpClearCsv.setOnClickListener {
            val file = csvManager.getFile()
            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(activity, "Experiment results are already empty!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(activity)
                .setTitle("Clear Experiment Results?")
                .setMessage("Delete all records from ${ExperimentCsvManager.EXPERIMENT_CSV_FILE_NAME}?")
                .setPositiveButton("Delete All") { _, _ ->
                    csvManager.clearResults()
                    refreshHistory()
                    Toast.makeText(activity, "Experiment results cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupRecyclerView() {
        rvExpHistory.layoutManager = LinearLayoutManager(activity)
    }

    fun refreshHistory() {
        val records = csvManager.readAllResults().reversed().toMutableList()
        if (adapter == null) {
            adapter = ExperimentAdapter(records) { item, pos ->
                AlertDialog.Builder(activity)
                    .setTitle("Trial Record")
                    .setMessage(
                        "Trial: #${item.trialIndex}/${item.totalTrials}\n" +
                        "Spot: ${item.actualSpot} (${item.actualX}m, ${item.actualY}m)\n" +
                        "Separation: ${item.separationMeters}m\n\n" +
                        "• k-NN: ${item.knnPredSpot} (${if (item.knnCorrect) "Correct" else "Wrong"}, Err: ${"%.2f".format(item.knnErrorMeters)}m)\n" +
                        "• Matrix: ${item.fuzzyPredSpot} (${if (item.fuzzyCorrect) "Correct" else "Wrong"}, Err: ${"%.2f".format(item.fuzzyErrorMeters)}m)" +
                        (if (item.patternPredSpot.isNotBlank()) "\n• Pattern: ${item.patternPredSpot} (${if (item.patternCorrect) "Correct" else "Wrong"}, Err: ${"%.2f".format(item.patternErrorMeters)}m)" else "") +
                        (if (item.mamdaniPredSpot.isNotBlank()) "\n• Mamdani FIS: ${item.mamdaniPredSpot} (${if (item.mamdaniCorrect) "Correct" else "Wrong"}, COG Err: ${"%.2f".format(item.mamdaniErrorMeters)}m)" else "")
                    )
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Delete Row") { _, _ ->
                        csvManager.deleteRecord(item)
                        adapter?.removeAt(pos)
                        updateHeaderStats(adapter?.records ?: emptyList())
                    }
                    .show()
            }
            rvExpHistory.adapter = adapter
        } else {
            adapter?.updateList(records)
        }
        updateHeaderStats(records)
    }

    private fun updateHeaderStats(records: List<ExperimentIterationResult>) {
        if (records.isEmpty()) {
            tvExpEmptyHistory.visibility = View.VISIBLE
            rvExpHistory.visibility = View.GONE
            tvExpHeaderStats.text = "Experiment (0 records)"
            tvExpKnnStats.text = "k-NN: --% (Err: --m)"
            tvExpFuzzyStats.text = "Matrix: --% (Err: --m)"
            tvExpPatternStats.text = "Pattern: --% (Err: --m)"
            tvExpMamdaniStats.text = "Mamdani: --% (Err: --m)"
        } else {
            tvExpEmptyHistory.visibility = View.GONE
            rvExpHistory.visibility = View.VISIBLE
            val knnCorr = records.count { it.knnCorrect }
            val fuzzyCorr = records.count { it.fuzzyCorrect }
            val knnAcc = (knnCorr.toDouble() / records.size) * 100.0
            val fuzzyAcc = (fuzzyCorr.toDouble() / records.size) * 100.0
            val knnAvgErr = records.map { it.knnErrorMeters }.average()
            val fuzzyAvgErr = records.map { it.fuzzyErrorMeters }.average()

            val patRecords = records.filter { it.patternPredSpot.isNotBlank() }
            val mamRecords = records.filter { it.mamdaniPredSpot.isNotBlank() }

            tvExpHeaderStats.text = "Total Records: ${records.size} trials"
            tvExpKnnStats.text = "k-NN: ${"%.1f".format(knnAcc)}% (${"%.2f".format(knnAvgErr)}m)"
            tvExpFuzzyStats.text = "Matrix: ${"%.1f".format(fuzzyAcc)}% (${"%.2f".format(fuzzyAvgErr)}m)"

            if (patRecords.isNotEmpty()) {
                val patAcc = (patRecords.count { it.patternCorrect }.toDouble() / patRecords.size) * 100.0
                val patErr = patRecords.map { it.patternErrorMeters }.average()
                tvExpPatternStats.text = "Pattern: ${"%.1f".format(patAcc)}% (${"%.2f".format(patErr)}m)"
            } else {
                tvExpPatternStats.text = "Pattern: --% (Err: --m)"
            }

            if (mamRecords.isNotEmpty()) {
                val mamAcc = (mamRecords.count { it.mamdaniCorrect }.toDouble() / mamRecords.size) * 100.0
                val mamErr = mamRecords.map { it.mamdaniErrorMeters }.average()
                tvExpMamdaniStats.text = "Mamdani: ${"%.1f".format(mamAcc)}% (${"%.2f".format(mamErr)}m)"
            } else {
                tvExpMamdaniStats.text = "Mamdani: --% (Err: --m)"
            }
        }
    }

    fun onScanProgress(trialIndex: Int, totalTrials: Int, scanIndex: Int, totalScans: Int) {
        layoutExpProgress.visibility = View.VISIBLE
        tvExpProgressTrial.text = "Trial $trialIndex / $totalTrials"
        tvExpProgressScan.text = "Scan $scanIndex / $totalScans"
        val pct = ((scanIndex.toDouble() / totalScans) * 100).toInt()
        pbExpScan.progress = pct
    }

    fun onTrialCompleted(result: ExperimentIterationResult, stats: ExperimentSummaryStats) {
        adapter?.addRecord(result)
        tvExpEmptyHistory.visibility = View.GONE
        rvExpHistory.visibility = View.VISIBLE
        tvExpKnnStats.text = "k-NN: ${"%.1f".format(stats.knnAccuracyPct)}% (${"%.2f".format(stats.knnAvgErrorMeters)}m)"
        tvExpFuzzyStats.text = "Matrix: ${"%.1f".format(stats.fuzzyAccuracyPct)}% (${"%.2f".format(stats.fuzzyAvgErrorMeters)}m)"
        tvExpPatternStats.text = "Pattern: ${"%.1f".format(stats.patternAccuracyPct)}% (${"%.2f".format(stats.patternAvgErrorMeters)}m)"
        tvExpMamdaniStats.text = "Mamdani: ${"%.1f".format(stats.mamdaniAccuracyPct)}% (${"%.2f".format(stats.mamdaniAvgErrorMeters)}m)"
    }

    fun onExperimentFinished(stats: ExperimentSummaryStats) {
        setRunningState(false)
        layoutExpProgress.visibility = View.GONE
        Toast.makeText(
            activity,
            "Done! k-NN: ${"%.1f".format(stats.knnAccuracyPct)}% | Matrix: ${"%.1f".format(stats.fuzzyAccuracyPct)}% | Pat: ${"%.1f".format(stats.patternAccuracyPct)}% | Mamdani: ${"%.1f".format(stats.mamdaniAccuracyPct)}%",
            Toast.LENGTH_LONG
        ).show()
    }

    fun onExperimentAborted() {
        setRunningState(false)
        layoutExpProgress.visibility = View.GONE
        Toast.makeText(activity, "Experiment stopped", Toast.LENGTH_SHORT).show()
    }

    private fun setRunningState(running: Boolean) {
        btnExpStart.visibility = if (running) View.GONE else View.VISIBLE
        btnExpStop.visibility = if (running) View.VISIBLE else View.GONE
        spinnerExpSeparation.isEnabled = !running
        spinnerExpSpot.isEnabled = !running
        editExpCoordX.isEnabled = !running
        editExpCoordY.isEnabled = !running
        editExpTrials.isEnabled = !running
        editExpScans.isEnabled = !running
        btnExpExportCsv.isEnabled = !running
        btnExpClearCsv.isEnabled = !running
    }
}

