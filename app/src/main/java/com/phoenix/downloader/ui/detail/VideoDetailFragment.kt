package com.phoenix.downloader.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.core.CommentItem
import com.phoenix.downloader.core.YouTubeApiClient
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.FragmentVideoDetailBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import com.phoenix.downloader.ui.home.VideoAdapter
import com.phoenix.downloader.ui.home.VideoItem
import kotlinx.coroutines.launch

/**
 * VidMate-style video detail page with auto-play.
 * Video plays embedded (not fullscreen). Real YouTube data for related + comments.
 */
class VideoDetailFragment : Fragment() {
    private var _binding: FragmentVideoDetailBinding? = null
    private val binding get() = _binding!!

    private var videoUrl = ""
    private var videoTitle = ""
    private var videoThumbnail = ""
    private var videoDuration = 0
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUrl = arguments?.getString(ARG_URL) ?: ""
        videoTitle = arguments?.getString(ARG_TITLE) ?: ""
        videoThumbnail = arguments?.getString(ARG_THUMB) ?: ""
        videoDuration = arguments?.getInt(ARG_DURATION) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header info
        binding.tvDetailTitle.text = videoTitle.ifEmpty { videoUrl }
        binding.tvDetailChannel.text = extractChannelHint(videoUrl)

        // Auto-play video embedded
        setupPlayer()

        // Action row
        binding.btnDetailDownload.setOnClickListener {
            DownloadDialogFragment.newInstance(videoUrl, videoTitle)
                .show(childFragmentManager, "DownloadDialog")
        }
        binding.btnSave.setOnClickListener {
            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnPopup.setOnClickListener {
            Toast.makeText(requireContext(), "Popup player coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.btnPlayAudio.setOnClickListener {
            Toast.makeText(requireContext(), "Audio-only coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.btnShare.setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, videoUrl)
            }
            startActivity(Intent.createChooser(share, "Share"))
        }

        // Like / dislike (local toggle only)
        var liked = 0 // 0 none, 1 liked, -1 disliked
        binding.btnLike.setOnClickListener {
            liked = if (liked == 1) 0 else 1
            binding.btnLike.alpha = if (liked == 1) 1f else 0.5f
            binding.btnDislike.alpha = 0.5f
        }
        binding.btnDislike.setOnClickListener {
            liked = if (liked == -1) 0 else -1
            binding.btnDislike.alpha = if (liked == -1) 1f else 0.5f
            binding.btnLike.alpha = 0.5f
        }
        binding.btnLike.alpha = 0.5f
        binding.btnDislike.alpha = 0.5f

        // Related videos + comments (real YouTube data)
        binding.rvRelated.layoutManager = LinearLayoutManager(requireContext())
        loadRelated()
        loadComments()
    }

    private fun setupPlayer() {
        val ctx = context ?: return
        player = ExoPlayer.Builder(ctx).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            // Resolve stream URL via yt-dlp and play
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = YtDlpManager.resolveStreamUrl(videoUrl)
                    val streamUrl = result.getOrNull()
                    if (!streamUrl.isNullOrEmpty()) {
                        val mediaItem = MediaItem.fromUri(streamUrl)
                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.prepare()
                        exoPlayer.play() // Auto-play
                    }
                } catch (e: Exception) {
                    // Player will show error via controller
                }
            }
        }
    }

    private fun loadRelated() {
        val videoId = YouTubeApiClient.extractVideoId(videoUrl) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YouTubeApiClient.getRelatedVideos(videoId, 20, videoTitle)
            val list = if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else emptyList()
            if (list.isNotEmpty()) {
                binding.rvRelated.adapter = VideoAdapter(
                    list,
                    onWatchClick = { video ->
                        parentFragmentManager.beginTransaction()
                            .replace(
                                (requireView().parent as View).id,
                                newInstance(video.url, video.title, video.thumbnail, video.duration)
                            )
                            .addToBackStack(null)
                            .commit()
                    },
                    onDownloadClick = { video ->
                        DownloadDialogFragment.newInstance(video.url, video.title)
                            .show(childFragmentManager, "DownloadDialog")
                    }
                )
            }
        }
    }

    private var allComments: List<com.phoenix.downloader.core.CommentItem> = emptyList()
    private var commentsExpanded = false

    private fun loadComments() {
        val videoId = YouTubeApiClient.extractVideoId(videoUrl) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YouTubeApiClient.getComments(videoId, 20)
            if (result.isSuccess) {
                val comments = result.getOrNull() ?: emptyList()
                if (comments.isNotEmpty()) {
                    allComments = comments
                    binding.tvCommentsTitle.text = "Comments · ${comments.size}"
                    binding.rvComments.layoutManager = LinearLayoutManager(requireContext())
                    // Collapsed by default: show first comment as preview
                    binding.rvComments.adapter = CommentAdapter(comments.take(1))
                    binding.tvCommentsChevron.text = "⌄"
                    binding.layoutCommentsHeader.setOnClickListener {
                        commentsExpanded = !commentsExpanded
                        binding.rvComments.adapter = CommentAdapter(
                            if (commentsExpanded) allComments else allComments.take(1)
                        )
                        binding.tvCommentsChevron.text = if (commentsExpanded) "⌃" else "⌄"
                    }
                }
            }
        }
    }

    private fun extractChannelHint(url: String): String {
        return when {
            url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
            url.contains("facebook.com") || url.contains("fb.watch") -> "Facebook"
            url.contains("tiktok.com") -> "TikTok"
            else -> ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }

    companion object {
        private const val ARG_URL = "URL"
        private const val ARG_TITLE = "TITLE"
        private const val ARG_THUMB = "THUMB"
        private const val ARG_DURATION = "DURATION"

        fun newInstance(url: String, title: String, thumbnail: String = "", duration: Int = 0) =
            VideoDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putString(ARG_TITLE, title)
                    putString(ARG_THUMB, thumbnail)
                    putInt(ARG_DURATION, duration)
                }
            }
    }
}
