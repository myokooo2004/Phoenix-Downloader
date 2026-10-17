package com.phoenix.downloader.ui.download

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.adapter.QualityAdapter
import com.phoenix.downloader.core.DownloadEngine
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.DialogDownloadBinding
import com.phoenix.downloader.model.FormatOption
import kotlinx.coroutines.launch
import java.io.File

/**
 * Step 2 of the URL flow: bottom sheet (mockup 4) with the video info,
 * path/storage row, Music quality grid, Video quality grid (144P -> 4K),
 * and the red DOWNLOAD button.
 *
 * Single selection across both grids: picking a music option clears the
 * video selection and vice versa.
 */
class DownloadDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogDownloadBinding? = null
    private val binding get() = _binding!!

    private var musicAdapter: QualityAdapter? = null
    private var videoAdapter: QualityAdapter? = null

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val videoUrl = requireArguments().getString(ARG_URL).orEmpty()
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        binding.tvDlgTitle.text = title
        binding.tvPath.text = getString(R.string.path_label) + downloadDir().absolutePath
        updateStorageInfo()

        binding.rvMusic.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvVideo.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.tvFetching.isVisible = true

        lifecycleScope.launch {
            val result = YtDlpManager.fetchFormats(videoUrl)
            binding.tvFetching.isVisible = false
            result.onSuccess { formats ->
                musicAdapter = QualityAdapter(formats.music) { onMusicSelected(it) }
                videoAdapter = QualityAdapter(formats.video) { onVideoSelected(it) }
                binding.rvMusic.adapter = musicAdapter
                binding.rvVideo.adapter = videoAdapter
                // Default: the preselected video option (360P in the mockup)
                videoAdapter?.selected?.let { onVideoSelected(it) }
            }.onFailure {
                Toast.makeText(requireContext(), R.string.fetch_failed, Toast.LENGTH_LONG).show()
                dismiss()
            }
        }

        binding.btnDownload.setOnClickListener {
            val chosen: FormatOption? =
                musicAdapter?.selected?.takeIf { isMusicActive() }
                    ?: videoAdapter?.selected?.takeIf { isVideoActive() }
            if (chosen == null) {
                Toast.makeText(requireContext(), R.string.fetch_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
            val ext = if (chosen.isAudio) "mp3" else "mp4"
            DownloadEngine.enqueue(videoUrl, chosen, "$safeName.$ext", downloadDir().absolutePath)
            Toast.makeText(requireContext(), R.string.status_downloading, Toast.LENGTH_SHORT).show()
            dismiss()
            (activity as? MainActivity)?.showDownloads()
        }
    }

    private var musicActive = false

    private fun isMusicActive() = musicActive
    private fun isVideoActive() = !musicActive

    private fun onMusicSelected(@Suppress("UNUSED_PARAMETER") option: FormatOption) {
        musicActive = true
        videoAdapter?.clearSelection()
    }

    private fun onVideoSelected(@Suppress("UNUSED_PARAMETER") option: FormatOption) {
        musicActive = false
        musicAdapter?.clearSelection()
    }

    private fun downloadDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PhoenixDownloader"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun updateStorageInfo() {
        val dir = downloadDir()
        val free = dir.usableSpace / (1024 * 1024 * 1024)
        val total = dir.totalSpace / (1024 * 1024 * 1024)
        binding.tvStorage.text = getString(R.string.storage_free, "$free GB", "$total GB")
        binding.pbStorage.progress =
            if (total > 0) ((total - free) * 100 / total).toInt() else 0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"

        fun newInstance(videoUrl: String, title: String) = DownloadDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, videoUrl)
                putString(ARG_TITLE, title)
            }
        }
    }
}
