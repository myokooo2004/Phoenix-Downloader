package com.phoenix.downloader.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.phoenix.downloader.core.CommentItem
import com.phoenix.downloader.databinding.ItemCommentBinding

class CommentAdapter(
    private val comments: List<CommentItem>
) : RecyclerView.Adapter<CommentAdapter.VH>() {
    class VH(val binding: ItemCommentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val comment = comments[position]
        holder.binding.tvCommentAuthor.text = comment.author
        holder.binding.tvCommentText.text = comment.text
        holder.binding.tvCommentLikes.text = "👍 ${comment.likeCount}"
        if (comment.avatarUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(comment.avatarUrl)
                .into(holder.binding.ivCommentAvatar)
        }
    }

    override fun getItemCount() = comments.size
}
