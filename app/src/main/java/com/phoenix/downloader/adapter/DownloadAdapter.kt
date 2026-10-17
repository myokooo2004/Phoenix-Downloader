package com.phoenix.downloader.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.phoenix.downloader.R
import com.phoenix.downloader.databinding.ItemDownloadBinding
import com.phoenix.downloader.model.DownloadItem

/** File rows on the Downloads screen (mockup 6). */
class DownloadAdapter(
    private val onActionClick: (DownloadItem) -> Unit,
    private val onMoreClick: (DownloadItem) -> Unit
) : ListAdapter<DownloadItem, DownloadAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DownloadItem) {
            val ctx = b.root.context
            b.tvFileName.text = item.title
            b.tvFileMeta.text = ctx.getString(
                R.string.file_meta,
                item.formatLabel,
                item.sizeLabel ?: "—",
                item.progressLabel
            )
            item.durationLabel?.let {
                b.tvFileDuration.text = it
                b.tvFileDuration.isVisible = true
            } ?: run { b.tvFileDuration.isVisible = false }

            val active = item.status == DownloadItem.Status.DOWNLOADING ||
                item.status == DownloadItem.Status.QUEUED
            b.pbFile.isVisible = active
            b.pbFile.progress = item.progress

            b.ivFileType.setImageResource(
                if (item.isAudio) R.drawable.ic_music else R.drawable.ic_play
            )
            b.btnFileAction.setImageResource(
                when (item.status) {
                    DownloadItem.Status.DOWNLOADING -> R.drawable.ic_pause
                    DownloadItem.Status.PAUSED -> R.drawable.ic_play
                    DownloadItem.Status.DONE -> R.drawable.ic_play
                    else -> R.drawable.ic_close
                }
            )
            b.btnFileAction.setOnClickListener { onActionClick(item) }
            b.btnFileMore.setOnClickListener { onMoreClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
            override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
        }
    }
}
