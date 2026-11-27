package com.example.wififingerprint

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.wififingerprint.strategy.RawSurveySpot
import java.util.Locale

class RoomCardAdapter(
    private var rooms: List<RawSurveySpot>,
    private var isReadOnly: Boolean,
    private val onRescan: (RawSurveySpot) -> Unit,
    private val onDelete: (RawSurveySpot) -> Unit
) : RecyclerView.Adapter<RoomCardAdapter.RoomViewHolder>() {

    class RoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRoomBadgeChar: TextView = view.findViewById(R.id.tvRoomBadgeChar)
        val tvRoomName: TextView = view.findViewById(R.id.tvRoomName)
        val tvRoomQualityBadge: TextView = view.findViewById(R.id.tvRoomQualityBadge)
        val tvRoomMeta: TextView = view.findViewById(R.id.tvRoomMeta)
        val btnRoomRescan: Button = view.findViewById(R.id.btnRoomRescan)
        val btnRoomDelete: Button = view.findViewById(R.id.btnRoomDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_card, parent, false)
        return RoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        val spot = rooms[position]
        val ctx = holder.itemView.context

        holder.tvRoomName.text = spot.spotLabel
        holder.tvRoomBadgeChar.text = spot.spotLabel.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "R"

        val scans = spot.totalScans
        val (qualityText, bgRes, textRes) = when {
            scans >= 15 -> Triple("Strong · $scans scans", R.color.status_success_bg, R.color.status_success)
            scans >= 8 -> Triple("Fair · $scans scans", R.color.status_warning_bg, R.color.status_warning)
            else -> Triple("Low · $scans scans", R.color.status_error_bg, R.color.status_error)
        }
        holder.tvRoomQualityBadge.text = qualityText
        holder.tvRoomQualityBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bgRes))
        holder.tvRoomQualityBadge.setTextColor(ContextCompat.getColor(ctx, textRes))

        val coordText = if (spot.coordX != 0.0 || spot.coordY != 0.0) {
            String.format(Locale.US, " · (%.1fm, %.1fm)", spot.coordX, spot.coordY)
        } else {
            ""
        }
        holder.tvRoomMeta.text = "${spot.measurements.size} Wi-Fi APs$coordText"

        holder.btnRoomRescan.visibility = if (isReadOnly) View.GONE else View.VISIBLE
        holder.btnRoomDelete.visibility = if (isReadOnly) View.GONE else View.VISIBLE

        holder.btnRoomRescan.setOnClickListener { onRescan(spot) }
        holder.btnRoomDelete.setOnClickListener { onDelete(spot) }
    }

    override fun getItemCount(): Int = rooms.size

    fun updateRooms(newRooms: List<RawSurveySpot>, readOnly: Boolean) {
        this.rooms = newRooms
        this.isReadOnly = readOnly
        notifyDataSetChanged()
    }
}
