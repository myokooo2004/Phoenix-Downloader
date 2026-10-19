package com.phoenix.downloader.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.FragmentPlayerBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var exoPlayer: ExoPlayer? = null
    private var videoUrl = ""
    private var videoTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUrl = arguments?.getString("URL") ?: ""
        videoTitle = arguments?.getString("TITLE") ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvPlayerTitle.text = videoTitle
        
        exoPlayer = ExoPlayer.Builder(requireContext()).build()
        binding.playerView.player = exoPlayer

        // A YouTube watch URL is a web page, not a media stream — ExoPlayer
        // cannot play it directly. Resolve the direct stream URL via yt-dlp
        // first (same engine the download flow uses).
        Toast.makeText(requireContext(), "Loading video...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YtDlpManager.resolveStreamUrl(videoUrl)
            result.onSuccess { streamUrl ->
                if (!streamUrl.isNullOrEmpty()) {
                    exoPlayer?.setMediaItem(MediaItem.fromUri(streamUrl))
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                } else {
                    Toast.makeText(requireContext(), "Could not resolve video stream", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Play failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnPlayerBack.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
        binding.btnPlayerDownload.setOnClickListener {
            DownloadDialogFragment.newInstance(videoUrl, videoTitle).show(childFragmentManager, "DownloadDialog")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        exoPlayer?.release()
        exoPlayer = null
        _binding = null
    }

    companion object {
        fun newInstance(url: String, title: String) = PlayerFragment().apply {
            arguments = Bundle().apply { putString("URL", url); putString("TITLE", title) }
        }
    }
}
