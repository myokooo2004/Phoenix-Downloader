package com.phoenix.downloader.ui.player

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.FragmentPlayerBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * Built-in player (Media3 ExoPlayer). Two modes:
 *  - STREAM: resolves a direct stream URL via yt-dlp, then plays.
 *  - LOCAL:  plays a downloaded file (incl. 4K MP4) straight from disk.
 */
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val mode = requireArguments().getString(ARG_MODE, MODE_STREAM)
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        binding.tvPlayerTitle.text = title
        binding.btnPlayerBack.setOnClickListener { parentFragmentManager.popBackStack() }

        if (mode == MODE_LOCAL) {
            // Downloaded file: no need to download again.
            binding.btnPlayerDownload.isVisible = false
            val path = requireArguments().getString(ARG_LOCAL_PATH).orEmpty()
            playUri(Uri.fromFile(File(path)))
        } else {
            val videoUrl = requireArguments().getString(ARG_VIDEO_URL).orEmpty()
            binding.btnPlayerDownload.setOnClickListener {
                (activity as? MainActivity)?.openDownloadDialog(videoUrl, title)
            }
            resolveAndPlay(videoUrl)
        }
    }

    private fun resolveAndPlay(videoUrl: String) {
        binding.tvPlayerStatus.isVisible = true
        binding.tvPlayerStatus.text = getString(R.string.loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val streamUrl = YtDlpManager.resolveStreamUrl(videoUrl).getOrNull()
            if (streamUrl.isNullOrEmpty()) {
                // Scaffold: no binary yet, so no real stream URL.
                binding.tvPlayerStatus.text = getString(R.string.stream_unavailable)
                return@launch
            }
            binding.tvPlayerStatus.isVisible = false
            playUri(Uri.parse(streamUrl))
        }
    }

    private fun playUri(uri: Uri) {
        val exo = ExoPlayer.Builder(requireContext()).build().also { player = it }
        binding.playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                binding.tvPlayerStatus.isVisible = true
                binding.tvPlayerStatus.text = error.message ?: getString(R.string.fetch_failed)
            }
        })
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroyView() {
        player?.release()
        player = null
        binding.playerView.player = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_VIDEO_URL = "video_url"
        private const val ARG_LOCAL_PATH = "local_path"
        private const val ARG_TITLE = "title"
        private const val MODE_STREAM = "stream"
        private const val MODE_LOCAL = "local"

        fun newStreamInstance(videoUrl: String, title: String) = PlayerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, MODE_STREAM)
                putString(ARG_VIDEO_URL, videoUrl)
                putString(ARG_TITLE, title)
            }
        }

        fun newLocalInstance(localPath: String, title: String) = PlayerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, MODE_LOCAL)
                putString(ARG_LOCAL_PATH, localPath)
                putString(ARG_TITLE, title)
            }
        }
    }
}
