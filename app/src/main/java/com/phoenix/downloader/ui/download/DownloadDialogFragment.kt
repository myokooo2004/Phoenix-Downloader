package com.phoenix.downloader.ui.download

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.phoenix.downloader.core.DownloadService
import com.phoenix.downloader.databinding.DialogDownloadBinding
import org.json.JSONObject

class DownloadDialogFragment : BottomSheetDialogFragment() {
    private var _binding: DialogDownloadBinding? = null
    private val binding get() = _binding!!
    private var videoUrl = ""
    private var videoTitle = ""
    private var selectedFormatId = "best"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUrl = arguments?.getString("URL") ?: ""
        videoTitle = arguments?.getString("TITLE") ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvDlgTitle.text = videoTitle
        if (!Python.isStarted()) Python.start(AndroidPlatform(requireContext()))
        fetchFormats()
        binding.btnDownload.setOnClickListener { startDownload() }
    }

    private fun fetchFormats() {
        binding.tvFetching.visibility = View.VISIBLE
        Thread {
            val py = Python.getInstance()
            val result = py.getModule("ytdlp_bridge").callAttr("get_formats", videoUrl).toString()
            requireActivity().runOnUiThread {
                binding.tvFetching.visibility = View.GONE
                val json = JSONObject(result)
                if (json.optBoolean("ok")) parseFormats(json) else Toast.makeText(requireContext(), "Error", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun parseFormats(json: JSONObject) {
        val formatsArray = json.getJSONArray("formats")
        val videoList = mutableListOf<FormatInfo>()
        val audioList = mutableListOf<FormatInfo>()

        for (i in 0 until formatsArray.length()) {
            val obj = formatsArray.getJSONObject(i)
            val formatId = obj.optString("format_id")
            val ext = obj.optString("ext")
            val height = obj.optInt("height", 0)
            val vcodec = obj.optString("vcodec")
            val acodec = obj.optString("acodec")
            val filesize = obj.optLong("filesize", 0)
            val sizeMb = if (filesize > 0) String.format("%.1f MB", filesize / (1024f * 1024f)) else "Unknown"

            if (vcodec != "none") {
                val label = if (height >= 2160) "4K (${ext})" else if (height > 0) "${height}p (${ext})" else "Video ($ext)"
                videoList.add(FormatInfo(formatId, label, sizeMb, false))
            } else if (vcodec == "none" && acodec != "none") {
                audioList.add(FormatInfo(formatId, "Audio ($ext)", sizeMb, true))
            }
        }

        if (videoList.isNotEmpty()) {
            binding.tvVideoHeader.visibility = View.VISIBLE
            binding.rvVideoFormats.layoutManager = LinearLayoutManager(requireContext())
            binding.rvVideoFormats.adapter = FormatAdapter(videoList) { selectedFormatId = it.id }
        }
        if (audioList.isNotEmpty()) {
            binding.tvAudioHeader.visibility = View.VISIBLE
            binding.rvAudioFormats.layoutManager = LinearLayoutManager(requireContext())
            binding.rvAudioFormats.adapter = FormatAdapter(audioList) { selectedFormatId = it.id }
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

    companion object {
        fun newInstance(url: String, title: String) = DownloadDialogFragment().apply {
            arguments = Bundle().apply { putString("URL", url); putString("TITLE", title) }
        }
    }
}
