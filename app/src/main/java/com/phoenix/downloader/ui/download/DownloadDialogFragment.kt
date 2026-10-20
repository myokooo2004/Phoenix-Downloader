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
    private var formatsClient: String? = null
    private var videoAdapterRef: FormatAdapter? = null
    private var audioAdapterRef: FormatAdapter? = null

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

    override fun onStart() {
        super.onStart()
        // Give the sheet a FIXED height (92% of screen) and force it expanded.
        // With wrap_content height, the weighted ScrollView measures at full
        // content height and pushes the DOWNLOAD button off the visible sheet
        // (field-observed: button invisible / list not scrollable).
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val screenHeight = resources.displayMetrics.heightPixels
        sheet.layoutParams.height = (screenHeight * 0.92).toInt()
        sheet.requestLayout()
        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
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
                formatsClient = formatsResult.clientUsed
                val videoList = mutableListOf<FormatInfo>()
                // Offer EVERY video format yt-dlp returns (up to 4K/8K),
                // each as an MP4 or MKV output container. The SOURCE may be
                // WEBM/VP9 (the only form 4K comes in) — the file the user
                // receives is still MP4/MKV via --merge-output-format.
                for (f in formatsResult.video) {
                    videoList.add(FormatInfo(f.id, "${f.label} (MP4)", f.sizeLabel, false))
                    videoList.add(FormatInfo("${f.id}|MKV", "${f.label} (MKV)", f.sizeLabel, false))
                }
                val audioList = mutableListOf<FormatInfo>()
                // MP3 only (user does not want WEBM/M4A raw formats)
                audioList.add(FormatInfo("MP3_128", "128K (MP3)", "~3MB", true))
                audioList.add(FormatInfo("MP3_192", "192K (MP3)", "~5MB", true))
                audioList.add(FormatInfo("MP3_320", "320K (MP3)", "~8MB", true))

                if (videoList.isNotEmpty()) {
                    binding.layoutVideoSection.visibility = View.VISIBLE
                    binding.rvVideoFormats.layoutManager = GridLayoutManager(requireContext(), 2)
                    val videoAdapter = FormatAdapter(videoList) { chosen ->
                        selectedFormatId = chosen.id
                        audioAdapterRef?.clearSelection()
                    }
                    videoAdapterRef = videoAdapter
                    binding.rvVideoFormats.adapter = videoAdapter
                    // Default selection: first video format only (VidMate-style).
                    videoAdapter.preselect(0)
                    selectedFormatId = videoList[0].id
                }
                if (audioList.isNotEmpty()) {
                    binding.layoutMusicSection.visibility = View.VISIBLE
                    binding.rvAudioFormats.layoutManager = GridLayoutManager(requireContext(), 2)
                    val audioAdapter = FormatAdapter(audioList) { chosen ->
                        selectedFormatId = chosen.id
                        videoAdapterRef?.clearSelection()
                    }
                    audioAdapterRef = audioAdapter
                    binding.rvAudioFormats.adapter = audioAdapter
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
            // Download with the same player_client that produced the format
            // list (e.g. tv / web_safari when the default capped at 360p).
            formatsClient?.let { putExtra("CLIENT", it) }
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
