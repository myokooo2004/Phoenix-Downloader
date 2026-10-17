package com.phoenix.downloader.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.phoenix.downloader.databinding.ItemVideoBinding
import com.phoenix.downloader.model.VideoInfo

/** Video cards on Home (mockup 3). The ⬇ button opens the download dialog. */
class VideoAdapter(
    private val onDownloadClick: (VideoInfo) -> Unit,
    private val onCardClick: (VideoInfo) -> Unit = {}
) : ListAdapter<VideoInfo, VideoAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: VideoInfo) {
            b.tvTitle.text = item.title
            b.tvSubtitle.text = "${item.uploader} · ${item.viewCount}"
            b.tvViews.text = item.viewCount
            b.tvDuration.text = item.durationLabel
            b.tvAvatar.text = item.uploader.firstOrNull()?.toString() ?: "▶"
            // TODO: load b.ivThumb from item.thumbnailUrl (Coil/Glide)
            b.btnItemDownload.setOnClickListener { onDownloadClick(item) }
            b.root.setOnClickListener { onCardClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoInfo>() {
            override fun areItemsTheSame(a: VideoInfo, b: VideoInfo) = a.url == b.url
            override fun areContentsTheSame(a: VideoInfo, b: VideoInfo) = a == b
        }
    }
}
