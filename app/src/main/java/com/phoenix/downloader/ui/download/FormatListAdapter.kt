package com.phoenix.downloader.ui.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.phoenix.downloader.R

/** Rows of the download dialog's single RecyclerView. */
sealed class FormatRow {
    data class Section(val title: String) : FormatRow()

    /**
     * @param height video height in px (0 for audio)
     * @param container output container the user picked: "MP4", "MKV" or "MP3"
     */
    data class Entry(
        val info: FormatInfo,
        val height: Int,
        val container: String
    ) : FormatRow()
}

/**
 * Single adapter for the whole format list (audio + video sections).
 * One shared selection across all entries — picking an audio entry clears
 * the video selection and vice versa (the old two-adapter setup auto-selected
 * one entry in EACH grid).
 */
class FormatListAdapter(
    private val rows: List<FormatRow>,
    private val onEntrySelected: (FormatRow.Entry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var selectedPosition = -1

    fun preselect(position: Int) {
        if (position in rows.indices && rows[position] is FormatRow.Entry) {
            selectedPosition = position
            notifyItemChanged(position)
        }
    }

    fun isSection(position: Int): Boolean = rows[position] is FormatRow.Section

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is FormatRow.Section) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            SectionVH(inflater.inflate(R.layout.item_format_section, parent, false))
        } else {
            EntryVH(inflater.inflate(R.layout.item_format, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is FormatRow.Section -> (holder as SectionVH).title.text = row.title
            is FormatRow.Entry -> {
                val vh = holder as EntryVH
                vh.quality.text = row.info.label
                vh.size.text = row.info.size
                vh.radio.isChecked = position == selectedPosition
                vh.itemView.setOnClickListener {
                    if (selectedPosition >= 0) notifyItemChanged(selectedPosition)
                    selectedPosition = holder.bindingAdapterPosition
                    notifyItemChanged(selectedPosition)
                    onEntrySelected(row)
                }
            }
        }
    }

    override fun getItemCount() = rows.size

    class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tv_section_title)
    }

    class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        val radio: RadioButton = v.findViewById(R.id.rb_select)
        val quality: TextView = v.findViewById(R.id.tv_quality)
        val size: TextView = v.findViewById(R.id.tv_size)
    }

    companion object {
        /** Grid span: sections take the full row, entries take one cell. */
        fun spanSizeLookup(adapter: FormatListAdapter) =
            object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isSection(position)) 2 else 1
            }
    }
}
