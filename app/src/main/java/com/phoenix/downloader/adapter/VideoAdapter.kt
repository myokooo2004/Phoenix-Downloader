package com.phoenix.downloader.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.phoenix.downloader.databinding.ItemVideoBinding

data class VideoItem(val url: String, val title: String, val thumbnail: String, val duration: Int)

class VideoAdapter(
    private val videos: List<VideoItem>,
    private val onWatchClick: (VideoItem) -> Unit,
    private val onDownloadClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {
    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val video = videos[position]
        holder.binding.tvTitle.text = video.title
        holder.binding.tvDuration.text = "${video.duration / 60}:${String.format("%02d", video.duration % 60)}"
        Glide.with(holder.itemView.context).load(video.thumbnail).into(holder.binding.ivThumb)
        
        holder.itemView.setOnClickListener { onWatchClick(video) }
        holder.binding.btnItemDownload.setOnClickListener { onDownloadClick(video) } 
    }
    override fun getItemCount() = videos.size
}
