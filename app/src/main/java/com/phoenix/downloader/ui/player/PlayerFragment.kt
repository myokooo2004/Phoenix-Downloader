package com.phoenix.downloader.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.phoenix.downloader.databinding.FragmentPlayerBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment

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
        exoPlayer?.setMediaItem(MediaItem.fromUri(videoUrl))
        exoPlayer?.prepare()
        exoPlayer?.play()

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
