package com.phoenix.downloader.ui.downloads

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.core.DownloadEngine
import com.phoenix.downloader.databinding.FragmentDownloadsBinding
import com.phoenix.downloader.model.DownloadItem
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen, two modes (user split, 2026-10-23):
 *  - MODE_ACTIVE ("Download" tab; also the home top bar's downloads
 *    button): in-progress downloads only — live progress / speed /
 *    ETA, pause, resume, cancel, driven by DownloadEngine.
 *  - MODE_DONE ("My Files" tab): completed downloads only — tap to
 *    play in the built-in player, share, delete.
 */
class DownloadsFragment : Fragment() {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private val adapter = DownloadsAdapter()

    private val mode: Int
        get() = arguments?.getInt(ARG_MODE, MODE_ACTIVE) ?: MODE_ACTIVE

    companion object {
        const val MODE_ACTIVE = 0
        const val MODE_DONE = 1
        private const val ARG_MODE = "mode"

        fun newInstance(mode: Int) = DownloadsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_MODE, mode) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloads.adapter = adapter
        binding.tvTitle.setText(
            if (mode == MODE_DONE) R.string.title_downloads else R.string.title_download_active
        )
        binding.tvEmpty.setText(
            if (mode == MODE_DONE) R.string.empty_files else R.string.empty_downloads
        )
        rebuild()
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadEngine.records.collect { rebuild() }
        }
        // Belt-and-braces: also refresh once a second while visible, so
        // live progress can never sit stale on screen.
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (_binding != null) rebuild()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) rebuild()
    }

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Active(val item: DownloadItem) : Row()
        data class Done(val item: DownloadItem?, val file: File) : Row()
    }

    private fun rebuild() {
        val b = _binding ?: return
        val records = DownloadEngine.records.value
        val active = records.filter { it.status != DownloadItem.Status.DONE }
        val doneRecords = records.filter {
            it.status == DownloadItem.Status.DONE &&
                it.filePath != null && File(it.filePath!!).exists()
        }
        val covered = doneRecords.map { it.filePath!! }.toSet()
        val activePrefixes = records
            .filter { it.status != DownloadItem.Status.DONE }
            .map { "[${it.id}]" }
        val ctx = context ?: return
        val dir = DownloadEngine.downloadDir(ctx)
        val scanned = (dir.listFiles()?.filter { f ->
            val ext = f.extension.lowercase()
            (ext == "mp4" || ext == "mkv" || ext == "mp3" || ext == "m4a" || ext == "webm") &&
                f.absolutePath !in covered &&
                activePrefixes.none { p -> f.name.contains(p) } &&
                !File(f.absolutePath + ".aria2").exists()
        }?.sortedByDescending { it.lastModified() } ?: emptyList())

        val rows = mutableListOf<Row>()
        if (mode == MODE_ACTIVE && active.isNotEmpty()) {
            rows.add(Row.Header("Downloading • ${active.size}"))
            active.forEach { rows.add(Row.Active(it)) }
        }
        val doneCount = doneRecords.size + scanned.size
        if (mode == MODE_DONE && doneCount > 0) {
            rows.add(Row.Header("Downloaded • $doneCount"))
            doneRecords.forEach { rows.add(Row.Done(it, File(it.filePath!!))) }
            scanned.forEach { rows.add(Row.Done(null, it)) }
        }

        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.rvDownloads.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(rows)
    }

    private inner class DownloadsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Active -> 1
            is Row.Done -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
                1 -> ActiveVH(inflater.inflate(R.layout.item_download_active, parent, false))
                else -> DoneVH(inflater.inflate(R.layout.item_download, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).tv.text = row.text
                is Row.Active -> bindActive(holder as ActiveVH, row.item)
                is Row.Done -> bindDone(holder as DoneVH, row)
            }
        }

        override fun getItemCount() = rows.size

        private fun bindActive(holder: ActiveVH, item: DownloadItem) {
            holder.title.text = item.title
            if (item.thumbnail.isNotEmpty()) {
                Glide.with(holder.itemView).load(item.thumbnail)
                    .error(android.R.drawable.ic_media_play).into(holder.thumb)
            } else {
                holder.thumb.setImageResource(android.R.drawable.ic_media_play)
            }
            holder.progress.isIndeterminate = item.status == DownloadItem.Status.QUEUED
            holder.progress.progress = item.progress
            holder.meta.text = when (item.status) {
                DownloadItem.Status.QUEUED -> "Queued • ${item.formatLabel}"
                DownloadItem.Status.DOWNLOADING ->
                    if (item.totalBytes > 0)
                        "${fmtBytes(item.downloadedBytes)} / ${fmtBytes(item.totalBytes)} • ${item.formatLabel}"
                    else "${fmtBytes(item.downloadedBytes)} • ${item.formatLabel}"
                DownloadItem.Status.PAUSED ->
                    "Paused • ${fmtBytes(item.downloadedBytes)} downloaded"
                DownloadItem.Status.FAILED ->
                    "Failed${item.errorMessage?.let { ": $it" } ?: ""}"
                DownloadItem.Status.DONE -> item.formatLabel
            }
            holder.speed.text = when {
                item.status == DownloadItem.Status.DOWNLOADING && item.speedBps > 0 ->
                    "${fmtBytes(item.speedBps)}/s"
                item.status == DownloadItem.Status.DOWNLOADING -> item.formatLabel
                else -> ""
            }
            holder.eta.text = when {
                item.status == DownloadItem.Status.DOWNLOADING && item.etaSec > 0 ->
                    "${fmtEta(item.etaSec)} left"
                else -> ""
            }
            holder.pauseBtn.text = when (item.status) {
                DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> "▶"
                else -> "⏸"
            }
            holder.pauseBtn.setOnClickListener {
                when (item.status) {
                    DownloadItem.Status.DOWNLOADING, DownloadItem.Status.QUEUED ->
                        DownloadEngine.pause(item.id)
                    DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED ->
                        DownloadEngine.resume(item.id)
                    else -> Unit
                }
            }
            holder.cancelBtn.setOnClickListener {
                DownloadEngine.cancel(item.id)
                Toast.makeText(requireContext(), "Removed", Toast.LENGTH_SHORT).show()
            }
        }

        private fun bindDone(holder: DoneVH, row: Row.Done) {
            val file = row.file
            val item = row.item
            holder.name.text = item?.title ?: file.nameWithoutExtension
            if (item != null && item.thumbnail.isNotEmpty()) {
                Glide.with(holder.itemView).load(item.thumbnail)
                    .error(android.R.drawable.ic_media_play).into(holder.thumb)
            } else if (!file.extension.equals("mp3", ignoreCase = true)) {
                Glide.with(holder.itemView).load(file)
                    .error(android.R.drawable.ic_media_play).into(holder.thumb)
            } else {
                holder.thumb.setImageResource(android.R.drawable.ic_media_play)
            }
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified()))
            val parts = mutableListOf(fmtBytes(file.length()), file.extension.uppercase(), date)
            if (item != null) parts.add(0, item.formatLabel)
            holder.meta.text = parts.joinToString(" | ")
            holder.itemView.setOnClickListener {
                (activity as? MainActivity)?.openLocalPlayer(file.absolutePath, holder.name.text.toString())
            }
            holder.menuBtn.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, "Play")
                popup.menu.add(0, 2, 1, "Share")
                popup.menu.add(0, 3, 2, "Delete")
                popup.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> (activity as? MainActivity)?.openLocalPlayer(
                            file.absolutePath, holder.name.text.toString()
                        )
                        2 -> shareFile(file)
                        3 -> {
                            if (item != null) DownloadEngine.deleteCompleted(item.id)
                            else runCatching { file.delete() }
                            rebuild()
                        }
                    }
                    true
                }
                popup.show()
            }
        }

        private fun shareFile(file: File) {
            try {
                val uri = FileProvider.getUriForFile(
                    requireContext(), requireContext().packageName + ".fileprovider", file
                )
                val mime = when (file.extension.lowercase()) {
                    "mp3", "m4a" -> "audio/*"
                    else -> "video/*"
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Share failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v as TextView
    }

    class ActiveVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: android.widget.ImageView = v.findViewById(R.id.iv_dl_thumb)
        val title: TextView = v.findViewById(R.id.tv_dl_title)
        val meta: TextView = v.findViewById(R.id.tv_dl_meta)
        val progress: ProgressBar = v.findViewById(R.id.pb_dl)
        val speed: TextView = v.findViewById(R.id.tv_dl_speed)
        val eta: TextView = v.findViewById(R.id.tv_dl_eta)
        val pauseBtn: TextView = v.findViewById(R.id.btn_dl_pause)
        val cancelBtn: TextView = v.findViewById(R.id.btn_dl_cancel)
    }

    class DoneVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: android.widget.ImageView = v.findViewById(R.id.iv_file_thumb)
        val name: TextView = v.findViewById(R.id.tv_file_name)
        val meta: TextView = v.findViewById(R.id.tv_file_meta)
        val menuBtn: TextView = v.findViewById(R.id.btn_file_menu)
    }

    companion object {
        fun fmtBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

        fun fmtEta(sec: Long): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
