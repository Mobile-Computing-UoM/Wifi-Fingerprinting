package com.example.wififingerprint

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.wififingerprint.feedback.FeedbackHistoryRecord

/**
 * RecyclerView adapter for displaying inference validation history with row-level delete.
 */
class FeedbackHistoryAdapter(
    val records: MutableList<FeedbackHistoryRecord>,
    private val onDeleteClick: (FeedbackHistoryRecord, Int) -> Unit
) : RecyclerView.Adapter<FeedbackHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvActualSpot: TextView = view.findViewById(R.id.tvHistoryActualSpot)
        val tvTime: TextView = view.findViewById(R.id.tvHistoryTime)
        val btnDelete: Button = view.findViewById(R.id.btnDeleteHistoryRow)
        val tvKnnResult: TextView = view.findViewById(R.id.tvHistoryKnnResult)
        val tvFuzzyResult: TextView = view.findViewById(R.id.tvHistoryFuzzyResult)
        val tvPatternResult: TextView = view.findViewById(R.id.tvHistoryPatternResult)
        val tvMamdaniResult: TextView = view.findViewById(R.id.tvHistoryMamdaniResult)
        val tvContext: TextView = view.findViewById(R.id.tvHistoryContext)
        val tvSignals: TextView = view.findViewById(R.id.tvHistorySignals)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_feedback_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = records[position]

        holder.tvActualSpot.text = "📍 Ground Truth: ${item.actualSpot}"
        holder.tvTime.text = if (item.isoTime.length >= 19) {
            item.isoTime.substring(11, 19) + " UTC"
        } else {
            item.isoTime
        }

        val knnStatus = if (item.knnCorrect) "✅ CORRECT" else "❌ WRONG"
        val knnGapStr = if (item.knnMargin > 0.0) " (Gap: ${"%.2f".format(item.knnMargin)} dBm)" else ""
        holder.tvKnnResult.text = "• Strategy 1 (k-NN): ${item.knnPredicted} [$knnStatus]$knnGapStr"

        val fuzzyStatus = if (item.fuzzyCorrect) "✅ CORRECT" else "❌ WRONG"
        val fuzzyMarginStr = if (item.fuzzyMargin > 0.0) " (Margin: +${"%.4f".format(item.fuzzyMargin)})" else ""
        holder.tvFuzzyResult.text = "• Strategy 2 (Fuzzy): ${item.fuzzyPredicted} [$fuzzyStatus]$fuzzyMarginStr"

        if (item.patternPredicted.isNotBlank()) {
            val patStatus = if (item.patternCorrect) "✅ CORRECT" else "❌ WRONG"
            val patMarginStr = if (item.patternMargin > 0.0) " (Sep: ${"%.2f".format(item.patternMargin)} dB)" else ""
            holder.tvPatternResult.visibility = View.VISIBLE
            holder.tvPatternResult.text = "• Strategy 3 (Pattern): ${item.patternPredicted} [$patStatus]$patMarginStr"
        } else {
            holder.tvPatternResult.visibility = View.GONE
        }

        if (item.mamdaniPredicted.isNotBlank()) {
            val mamStatus = if (item.mamdaniCorrect) "✅ CORRECT" else "❌ WRONG"
            val mamMarginStr = if (item.mamdaniMargin > 0.0) " (Margin: +${"%.4f".format(item.mamdaniMargin)})" else ""
            holder.tvMamdaniResult.visibility = View.VISIBLE
            holder.tvMamdaniResult.text = "• Strategy 4 (Mamdani FIS): ${item.mamdaniPredicted} [$mamStatus]$mamMarginStr"
        } else {
            holder.tvMamdaniResult.visibility = View.GONE
        }

        val calibInfo = item.calibOffsetDb?.let { " | Calib: %+.1f dB".format(it) } ?: ""
        val devInfo = if (item.refDeviceModel.isNotBlank()) " (${item.refDeviceModel})" else ""
        holder.tvContext.text = "📱 Map: ${item.mapSource}$devInfo$calibInfo"

        // Format signals neatly
        val formattedSignals = item.querySignals.replace(";", " | ")
        holder.tvSignals.text = "📡 Signals: $formattedSignals"

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

    fun updateList(newRecords: List<FeedbackHistoryRecord>) {
        records.clear()
        records.addAll(newRecords)
        notifyDataSetChanged()
    }
}
