package com.example.wififingerprint

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Data structure representing a survey session's train/test split validation record.
 */
data class SurveyCalibrationRecord(
    val timestampMs: Long,
    val isoTime: String,
    val spotLabel: String,
    val totalScans: Int,
    val trainScans: Int,
    val testScans: Int,
    val knnAccuracy: Double,
    val fuzzyAccuracy: Double,
    val autoCalibrated: String,
    val rawCsvLine: String,
    val mamdaniAccuracy: Double = -1.0,
    val hybridAccuracy: Double = -1.0,
    val selectedEngine: String = ""
)

/**
 * RecyclerView adapter for displaying survey calibration accuracy history with row deletion.
 */
class SurveyCalibrationAdapter(
    val records: MutableList<SurveyCalibrationRecord>,
    private val onDeleteClick: (SurveyCalibrationRecord, Int) -> Unit
) : RecyclerView.Adapter<SurveyCalibrationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSpotName: TextView = view.findViewById(R.id.tvCalibSpotName)
        val tvTime: TextView = view.findViewById(R.id.tvCalibTime)
        val btnDelete: Button = view.findViewById(R.id.btnDeleteCalibRow)
        val tvScanSplit: TextView = view.findViewById(R.id.tvCalibScanSplit)
        val tvKnnAcc: TextView = view.findViewById(R.id.tvCalibKnnAcc)
        val tvFuzzyAcc: TextView = view.findViewById(R.id.tvCalibFuzzyAcc)
        val tvMamdaniAcc: TextView = view.findViewById(R.id.tvCalibMamdaniAcc)
        val tvHybridAcc: TextView = view.findViewById(R.id.tvCalibHybridAcc)
        val tvAutoBadge: TextView = view.findViewById(R.id.tvCalibAutoBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_survey_calibration, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = records[position]

        holder.tvSpotName.text = "📍 Spot: ${item.spotLabel}"
        holder.tvTime.text = if (item.isoTime.length >= 19) {
            item.isoTime.substring(11, 19) + " UTC"
        } else {
            item.isoTime
        }

        val engineNote = if (item.selectedEngine.isNotBlank()) " · Active: ${item.selectedEngine}" else ""
        holder.tvScanSplit.text = "📊 Scans: ${item.totalScans} Total (${item.trainScans} Build / ${item.testScans} Held-Out Test)$engineNote"
        val knnTag = if (item.selectedEngine.equals("WKNN", ignoreCase = true)) " ★" else ""
        val mamTag = if (item.selectedEngine.equals("Mamdani FIS", ignoreCase = true)) " ★" else ""
        val hybTag = if (item.selectedEngine.equals("Smart Hybrid", ignoreCase = true)) " ★" else ""
        holder.tvKnnAcc.text = "• Strategy 1 (WKNN): ${"%.1f".format(item.knnAccuracy)}%$knnTag"
        holder.tvFuzzyAcc.text = "• Strategy 2 (Fuzzy Matrix): ${"%.1f".format(item.fuzzyAccuracy)}%"

        if (item.mamdaniAccuracy >= 0.0) {
            holder.tvMamdaniAcc.visibility = View.VISIBLE
            holder.tvMamdaniAcc.text = "• Strategy 4 (Mamdani FIS): ${"%.1f".format(item.mamdaniAccuracy)}%$mamTag"
        } else {
            holder.tvMamdaniAcc.visibility = View.GONE
        }

        if (item.hybridAccuracy >= 0.0) {
            holder.tvHybridAcc.visibility = View.VISIBLE
            holder.tvHybridAcc.text = "• Smart Hybrid (WKNN + FIS): ${"%.1f".format(item.hybridAccuracy)}%$hybTag"
        } else {
            holder.tvHybridAcc.visibility = View.GONE
        }

        holder.tvAutoBadge.text = "⚡ Auto-Calibration: ${item.autoCalibrated}"

        holder.btnDelete.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onDeleteClick(item, currentPos)
            }
        }
    }

    override fun getItemCount(): Int = records.size

    fun removeAt(position: Int) {
        if (position in records.indices) {
            records.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun updateList(newRecords: List<SurveyCalibrationRecord>) {
        records.clear()
        records.addAll(newRecords)
        notifyDataSetChanged()
    }
}
