package com.phoenix.downloader.ui.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.phoenix.downloader.R

data class FormatInfo(val id: String, val label: String, val size: String, val isAudio: Boolean)

class FormatAdapter(private val formats: List<FormatInfo>, private val onItemSelected: (FormatInfo) -> Unit) : RecyclerView.Adapter<FormatAdapter.VH>() {
    // -1 = nothing selected. The dialog coordinates ONE selection across the
    // audio and video grids: selecting in one grid clears the other.
    private var selectedPosition = -1

    /** Select an item WITHOUT firing the callback (used for the initial default). */
    fun preselect(position: Int) {
        if (position in formats.indices) {
            selectedPosition = position
            notifyItemChanged(position)
        }
    }

    fun clearSelection() {
        if (selectedPosition >= 0) {
            val old = selectedPosition
            selectedPosition = -1
            notifyItemChanged(old)
        }
    }

    fun selectedItem(): FormatInfo? = formats.getOrNull(selectedPosition)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rbSelect: RadioButton = v.findViewById(R.id.rb_select)
        val tvQuality: TextView = v.findViewById(R.id.tv_quality)
        val tvSize: TextView = v.findViewById(R.id.tv_size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_format, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val format = formats[position]
        holder.tvQuality.text = format.label
        holder.tvSize.text = format.size
        holder.rbSelect.isChecked = (position == selectedPosition)

        holder.itemView.setOnClickListener {
            if (selectedPosition >= 0) notifyItemChanged(selectedPosition)
            selectedPosition = holder.adapterPosition
            notifyItemChanged(selectedPosition)
            onItemSelected(format)
        }
    }
    override fun getItemCount() = formats.size
}
