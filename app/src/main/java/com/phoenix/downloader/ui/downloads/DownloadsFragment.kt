package com.phoenix.downloader.ui.downloads

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.adapter.DownloadAdapter
import com.phoenix.downloader.core.DownloadEngine
import com.phoenix.downloader.databinding.FragmentDownloadsBinding
import com.phoenix.downloader.model.DownloadItem
import java.io.File

/**
 * Downloads screen (mockup 6): All / Video / Music tabs, storage bar,
 * file list. Tapping play on a finished file opens the built-in
 * ExoPlayer in local-file mode (4K MP4 supported).
 */
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DownloadAdapter
    private val items = mutableListOf<DownloadItem>()
    private var filter: Filter = Filter.ALL

    private enum class Filter { ALL, VIDEO, MUSIC }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DownloadAdapter(
            onActionClick = ::onActionClick,
            onMoreClick = ::onMoreClick
        )
        binding.rvDownloads.adapter = adapter

        binding.tabAll.setOnClickListener { setFilter(Filter.ALL) }
        binding.tabVideo.setOnClickListener { setFilter(Filter.VIDEO) }
        binding.tabMusic.setOnClickListener { setFilter(Filter.MUSIC) }

        updateStorageBar()
        refreshList()

        DownloadEngine.setListener(object : DownloadEngine.Listener {
            override fun onProgress(item: DownloadItem) = upsert(item)
            override fun onComplete(item: DownloadItem) = upsert(item)
            override fun onFailed(item: DownloadItem, reason: String) = upsert(item)
        })
    }

    private fun setFilter(f: Filter) {
        filter = f
        val active = R.drawable.bg_tab_active
        val idle = R.drawable.bg_tab
        binding.tabAll.setBackgroundResource(if (f == Filter.ALL) active else idle)
        binding.tabVideo.setBackgroundResource(if (f == Filter.VIDEO) active else idle)
        binding.tabMusic.setBackgroundResource(if (f == Filter.MUSIC) active else idle)
        binding.tabAll.setTextColor(requireContext().getColor(if (f == Filter.ALL) R.color.white else R.color.text_secondary))
        binding.tabVideo.setTextColor(requireContext().getColor(if (f == Filter.VIDEO) R.color.white else R.color.text_secondary))
        binding.tabMusic.setTextColor(requireContext().getColor(if (f == Filter.MUSIC) R.color.white else R.color.text_secondary))
        refreshList()
    }

    private fun upsert(item: DownloadItem) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) items[i] = item else items.add(0, item)
        refreshList()
    }

    private fun refreshList() {
        val filtered = when (filter) {
            Filter.ALL -> items
            Filter.VIDEO -> items.filter { !it.isAudio }
            Filter.MUSIC -> items.filter { it.isAudio }
        }
        adapter.submitList(filtered.toList())
        binding.tvEmpty.isVisible = filtered.isEmpty()
        binding.rvDownloads.isVisible = filtered.isNotEmpty()
        binding.tvFileCount.text = getString(R.string.files_count, filtered.size)
    }

    private fun onActionClick(item: DownloadItem) {
        when (item.status) {
            DownloadItem.Status.DONE -> {
                // Play the downloaded file locally (4K MP4 included).
                (activity as? MainActivity)?.openPlayerLocal(item.destPath, item.title)
            }
            DownloadItem.Status.DOWNLOADING -> DownloadEngine.pause(item.id)
            DownloadItem.Status.PAUSED -> DownloadEngine.resume(item.id)
            else -> DownloadEngine.cancel(item.id)
        }
    }

    private fun onMoreClick(item: DownloadItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                DownloadEngine.cancel(item.id)
                File(item.destPath).delete()
                items.removeAll { it.id == item.id }
                refreshList()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun updateStorageBar() {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val stat = StatFs(path.absolutePath)
        val free = stat.availableBytes / (1024 * 1024 * 1024)
        val total = stat.totalBytes / (1024 * 1024 * 1024)
        binding.tvStorageInfo.text = getString(R.string.storage_free, "$free GB", "$total GB")
        binding.pbStorageTotal.progress =
            if (total > 0) ((total - free) * 100 / total).toInt() else 0
    }

    override fun onDestroyView() {
        DownloadEngine.setListener(null)
        super.onDestroyView()
        _binding = null
    }
}
