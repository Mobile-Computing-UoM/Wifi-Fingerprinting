package com.example.wififingerprint.experiment

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

        val knnIcon = if (item.knnCorrect) "✅" else "❌"
        val knnErrStr = "${"%.2f".format(Locale.US, item.knnErrorMeters)}m"
        holder.tvKnnResult.text = "k-NN: ${item.knnPredSpot} $knnIcon ($knnErrStr)"
        holder.tvKnnResult.setTextColor(if (item.knnCorrect) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))

        val fuzzyIcon = if (item.fuzzyCorrect) "✅" else "❌"
        val fuzzyErrStr = "${"%.2f".format(Locale.US, item.fuzzyErrorMeters)}m"
        holder.tvFuzzyResult.text = "Fuzzy: ${item.fuzzyPredSpot} $fuzzyIcon ($fuzzyErrStr)"
        holder.tvFuzzyResult.setTextColor(if (item.fuzzyCorrect) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))

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

