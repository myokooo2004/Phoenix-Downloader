package com.phoenix.downloader.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.phoenix.downloader.R
import com.phoenix.downloader.databinding.ItemQualityBinding
import com.phoenix.downloader.model.FormatOption

/**
 * Quality option rows in the download dialog grids (mockup 4).
 * Single-selection within its grid; the preselected option (360P)
 * starts checked.
 */
class QualityAdapter(
    options: List<FormatOption>,
    private val onSelect: (FormatOption) -> Unit
) : RecyclerView.Adapter<QualityAdapter.VH>() {

    private val items = options.toList()
    var selectedId: String? = items.firstOrNull { it.preselected }?.id ?: items.firstOrNull()?.id
        private set

    val selected: FormatOption? get() = items.firstOrNull { it.id == selectedId }

    /** Deselect everything (used when the other grid takes the selection). */
    fun clearSelection() {
        if (selectedId != null) {
            selectedId = null
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemQualityBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemQualityBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: FormatOption) {
            val isSel = item.id == selectedId
            b.tvQuality.text = item.displayLabel
            b.tvSize.text = item.sizeLabel
            b.rbSelect.isChecked = isSel
            b.tvQuality.setTextColor(
                b.root.context.getColor(if (isSel) R.color.red_accent else R.color.text_primary)
            )
            b.root.setOnClickListener {
                if (selectedId != item.id) {
                    selectedId = item.id
                    notifyDataSetChanged()
                    onSelect(item)
                }
            }
        }
    }
}
