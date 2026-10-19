package com.phoenix.downloader.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.core.YouTubeApiClient
import com.phoenix.downloader.databinding.FragmentVideoDetailBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import com.phoenix.downloader.ui.home.VideoAdapter
import com.phoenix.downloader.ui.home.VideoItem
import com.phoenix.downloader.ui.player.PlayerFragment
import kotlinx.coroutines.launch

/**
 * VidMate-style video detail page.
 * Opened when tapping a video from Home. Shows thumbnail, title, channel,
 * like/dislike, action row (Save/Popup/Play Audio/Share/Download), comments
 * header and related videos.
 */
class VideoDetailFragment : Fragment() {
    private var _binding: FragmentVideoDetailBinding? = null
    private val binding get() = _binding!!

    private var videoUrl = ""
    private var videoTitle = ""
    private var videoThumbnail = ""
    private var videoDuration = 0

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
        binding.tvDetailDuration.text = formatDuration(videoDuration)
        if (videoThumbnail.isNotEmpty()) {
            Glide.with(this).load(videoThumbnail).into(binding.ivDetailThumb)
        }

        // Tap thumbnail -> play
        binding.detailThumbContainer.setOnClickListener { openPlayer() }

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

        // Related videos
        binding.rvRelated.layoutManager = LinearLayoutManager(requireContext())
        loadRelated()
    }

    private fun openPlayer() {
        (activity as? MainActivity)?.openPlayer(videoUrl, videoTitle)
            ?: run {
                // Fallback: add PlayerFragment directly
                parentFragmentManager.beginTransaction()
                    .replace((view?.parent as? View)?.id ?: android.R.id.content,
                        PlayerFragment.newInstance(videoUrl, videoTitle))
                    .addToBackStack(null)
                    .commit()
            }
    }

    private fun loadRelated() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YouTubeApiClient.getTrending("MM", 10)
            val list = if (result.isSuccess) {
                (result.getOrNull() ?: emptyList()).filter { it.url != videoUrl }
            } else emptyList()
            if (list.isNotEmpty()) {
                binding.rvRelated.adapter = VideoAdapter(
                    list,
                    onWatchClick = { video ->
                        // Open detail for the tapped related video
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

    private fun extractChannelHint(url: String): String {
        return when {
            url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
            url.contains("facebook.com") || url.contains("fb.watch") -> "Facebook"
            url.contains("tiktok.com") -> "TikTok"
            else -> ""
        }
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
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
