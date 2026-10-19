package com.phoenix.downloader.ui.download

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.phoenix.downloader.core.DownloadService
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.DialogDownloadBinding
import kotlinx.coroutines.launch

class DownloadDialogFragment : BottomSheetDialogFragment() {
    private var _binding: DialogDownloadBinding? = null
    private val binding get() = _binding!!
    private var videoUrl = ""
    private var videoTitle = ""
    private var videoThumbnail = ""
    private var videoDuration = 0
    private var selectedFormatId = "best"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUrl = arguments?.getString("URL") ?: ""
        videoTitle = arguments?.getString("TITLE") ?: ""
        videoThumbnail = arguments?.getString("THUMB") ?: ""
        videoDuration = arguments?.getInt("DURATION") ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvDlgTitle.text = videoTitle
        if (videoThumbnail.isNotEmpty()) {
            Glide.with(this).load(videoThumbnail).into(binding.ivDlgThumb)
        }
        if (videoDuration > 0) {
            val m = videoDuration / 60
            val s = videoDuration % 60
            binding.tvDlgDuration.text = "%d:%02d".format(m, s)
        }
        fetchFormats()
        binding.btnDownload.setOnClickListener { startDownload() }
    }

    private fun fetchFormats() {
        binding.tvFetching.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = YtDlpManager.fetchFormats(videoUrl)
            binding.tvFetching.visibility = View.GONE
            result.onSuccess { formatsResult ->
                val videoList = formatsResult.video.map {
                    FormatInfo(it.id, "${it.label} (${it.format})", it.sizeLabel, false)
                }
                val audioList = formatsResult.music.map {
                    FormatInfo(it.id, "${it.label} (${it.format})", it.sizeLabel, true)
                }

                if (videoList.isNotEmpty()) {
                    binding.layoutVideoSection.visibility = View.VISIBLE
                    binding.rvVideoFormats.layoutManager = GridLayoutManager(requireContext(), 2)
                    binding.rvVideoFormats.adapter = FormatAdapter(videoList) { selectedFormatId = it.id }
                }
                if (audioList.isNotEmpty()) {
                    binding.layoutMusicSection.visibility = View.VISIBLE
                    binding.rvAudioFormats.layoutManager = GridLayoutManager(requireContext(), 2)
                    binding.rvAudioFormats.adapter = FormatAdapter(audioList) { selectedFormatId = it.id }
                }
                if (videoList.isEmpty() && audioList.isEmpty()) {
                    Toast.makeText(requireContext(), "No formats found", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(requireContext(), "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDownload() {
        val intent = Intent(requireContext(), DownloadService::class.java).apply {
            putExtra("URL", videoUrl)
            putExtra("TITLE", videoTitle)
            putExtra("FORMAT_ID", selectedFormatId)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        dismiss()
        Toast.makeText(requireContext(), "Download စတင်ပါပြီ", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(url: String, title: String, thumbnail: String = "", duration: Int = 0) = DownloadDialogFragment().apply {
            arguments = Bundle().apply {
                putString("URL", url)
                putString("TITLE", title)
                putString("THUMB", thumbnail)
                putInt("DURATION", duration)
            }
        }
    }
}
