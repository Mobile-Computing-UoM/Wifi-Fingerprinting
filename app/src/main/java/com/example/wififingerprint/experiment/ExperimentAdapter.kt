package com.example.wififingerprint.experiment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.wififingerprint.R
import java.util.Locale

class ExperimentAdapter(
    val records: MutableList<ExperimentIterationResult>,
    private val onItemClick: ((ExperimentIterationResult, Int) -> Unit)? = null
) : RecyclerView.Adapter<ExperimentAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTrialNumber: TextView = view.findViewById(R.id.tvExpTrialNumber)
        val tvActualSpot: TextView = view.findViewById(R.id.tvExpActualSpot)
        val tvSeparationBadge: TextView = view.findViewById(R.id.tvExpSeparationBadge)
        val tvKnnResult: TextView = view.findViewById(R.id.tvExpKnnResult)
        val tvFuzzyResult: TextView = view.findViewById(R.id.tvExpFuzzyResult)
        val layoutRow2: View = view.findViewById(R.id.layoutExpRow2)
        val tvPatternResult: TextView = view.findViewById(R.id.tvExpPatternResult)
        val tvMamdaniResult: TextView = view.findViewById(R.id.tvExpMamdaniResult)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_experiment_iteration, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = records[position]
        holder.tvTrialNumber.text = "Trial #${item.trialIndex}/${item.totalTrials}"
        holder.tvActualSpot.text = "Spot: ${item.actualSpot} (${"%.1f".format(Locale.US, item.actualX)}m, ${"%.1f".format(Locale.US, item.actualY)}m)"
        holder.tvSeparationBadge.text = "${"%.1f".format(Locale.US, item.separationMeters)}m"

        val ctx = holder.itemView.context
        val colorSuccess = ContextCompat.getColor(ctx, R.color.status_success)
        val colorError = ContextCompat.getColor(ctx, R.color.status_error)

        val knnIcon = if (item.knnCorrect) "✅" else "❌"
        val knnErrStr = "${"%.2f".format(Locale.US, item.knnErrorMeters)}m"
        holder.tvKnnResult.text = "k-NN: ${item.knnPredSpot} $knnIcon ($knnErrStr)"
        holder.tvKnnResult.setTextColor(if (item.knnCorrect) colorSuccess else colorError)

        val fuzzyIcon = if (item.fuzzyCorrect) "✅" else "❌"
        val fuzzyErrStr = "${"%.2f".format(Locale.US, item.fuzzyErrorMeters)}m"
        holder.tvFuzzyResult.text = "Matrix: ${item.fuzzyPredSpot} $fuzzyIcon ($fuzzyErrStr)"
        holder.tvFuzzyResult.setTextColor(if (item.fuzzyCorrect) colorSuccess else colorError)

        val hasRow2 = item.patternPredSpot.isNotBlank() || item.mamdaniPredSpot.isNotBlank()
        holder.layoutRow2.visibility = if (hasRow2) View.VISIBLE else View.GONE
        if (hasRow2) {
            if (item.patternPredSpot.isNotBlank()) {
                val patIcon = if (item.patternCorrect) "✅" else "❌"
                val patErrStr = "${"%.2f".format(Locale.US, item.patternErrorMeters)}m"
                holder.tvPatternResult.text = "Pattern: ${item.patternPredSpot} $patIcon ($patErrStr)"
                holder.tvPatternResult.setTextColor(if (item.patternCorrect) colorSuccess else colorError)
            } else {
                holder.tvPatternResult.text = "Pattern: --"
            }

            if (item.mamdaniPredSpot.isNotBlank()) {
                val mamIcon = if (item.mamdaniCorrect) "✅" else "❌"
                val mamErrStr = "${"%.2f".format(Locale.US, item.mamdaniErrorMeters)}m"
                holder.tvMamdaniResult.text = "Mamdani: ${item.mamdaniPredSpot} $mamIcon ($mamErrStr)"
                holder.tvMamdaniResult.setTextColor(if (item.mamdaniCorrect) colorSuccess else colorError)
            } else {
                holder.tvMamdaniResult.text = "Mamdani: --"
            }
        }

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(item, position)
        }
    }

    override fun getItemCount(): Int = records.size

    fun addRecord(record: ExperimentIterationResult) {
        records.add(0, record)
        notifyItemInserted(0)
    }

    fun updateList(newList: List<ExperimentIterationResult>) {
        records.clear()
        records.addAll(newList)
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        if (position in records.indices) {
            records.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}

