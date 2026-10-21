package com.phoenix.downloader.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.DialogDownloadBinding
import kotlinx.coroutines.launch

class DownloadDialogFragment : DialogFragment() {
    private var _binding: DialogDownloadBinding? = null
    private val binding get() = _binding!!
    private var videoUrl = ""
    private var videoTitle = ""
    private var videoThumbnail = ""
    private var videoDuration = 0
    private var selectedFormatId = "best"
    private var selectedHeight = 0
    private var selectedContainer = "MP4"
    private var formatsClient: String? = null
    private var displayTitle = ""

    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onFolderPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Plain dialog with a translucent theme: the WINDOW gets an exact
        // size (see onStart) and our match_parent root fills it. (The
        // BottomSheetDialog + wrap_content + weighted-ScrollView combination
        // repeatedly clipped the content and hid the DOWNLOAD button in the
        // field across builds 242-248, so the sheet machinery is gone.)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar)
        videoUrl = arguments?.getString("URL") ?: ""
        videoTitle = arguments?.getString("TITLE") ?: ""
        displayTitle = videoTitle
        videoThumbnail = arguments?.getString("THUMB") ?: ""
        videoDuration = arguments?.getInt("DURATION") ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Exact window: full width, 92% of screen height, anchored to the
        // bottom. Deterministic — no behavior/fitToContents re-measurement.
        dialog?.window?.apply {
            val h = (resources.displayMetrics.heightPixels * 0.92).toInt()
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                h
            )
            setGravity(android.view.Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.5f }
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
        // DOWNLOAD stays disabled until a format is actually selected —
        // tapping it early used to enqueue a junk "best" download.
        binding.btnDownload.isEnabled = false
        binding.btnDownload.alpha = 0.5f
        binding.btnDownload.setOnClickListener { startDownload() }
        updatePathRow()
        binding.tvDlgRename.setOnClickListener { showRenameDialog() }
        binding.tvDlgChange.setOnClickListener { folderPicker.launch(null) }
    }

    /** Rename: edit the display/file name before downloading. */
    private fun showRenameDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            setText(displayTitle)
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    displayTitle = name
                    binding.tvDlgTitle.text = name
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Path row: real destination + real free/total storage. */
    private fun updatePathRow() {
        val ctx = requireContext()
        val custom = ctx.getSharedPreferences("phoenix_downloads", android.content.Context.MODE_PRIVATE)
            .getString("custom_dir_path", null)
        binding.tvDlgPath.text = "Path:" + (custom ?: "/storage/emulated/0/Download/PhoenixDownloader")
        try {
            val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            binding.tvDlgStorage.text = "${formatGb(free)} FREE/${formatGb(total)}"
            binding.pbDlgStorage.max = 100
            binding.pbDlgStorage.progress =
                if (total > 0) ((total - free) * 100 / total).toInt() else 0
        } catch (e: Exception) { /* storage line is decorative */ }
    }

    private fun formatGb(bytes: Long): String = "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))

    /** Folder picked via the system picker: persist a real filesystem path. */
    private fun onFolderPicked(uri: android.net.Uri) {
        // Tree URIs look like /tree/primary:Download/Sub — only the primary
        // (internal) volume maps to a plain filesystem path yt-dlp can use.
        val path = uri.path ?: ""
        val marker = "/tree/primary:"
        val idx = path.indexOf(marker)
        if (idx >= 0) {
            val rel = path.substring(idx + marker.length)
            val real = android.os.Environment.getExternalStorageDirectory().absolutePath +
                if (rel.isEmpty()) "" else "/$rel"
            requireContext().getSharedPreferences("phoenix_downloads", android.content.Context.MODE_PRIVATE)
                .edit().putString("custom_dir_path", real).apply()
            updatePathRow()
            // Android 11+: raw-path writes to shared folders need the
            // all-files-access grant — send the user to the settings page.
            if (android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                Toast.makeText(
                    requireContext(),
                    "Allow \"All files access\" so downloads can save to that folder",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + requireContext().packageName)
                        )
                    )
                } catch (e: Exception) { /* engine falls back safely */ }
            } else {
                Toast.makeText(requireContext(), "Save to: $real", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                requireContext(),
                "That folder can't be used — internal storage folders only",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun fetchFormats() {
        binding.tvFetching.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = YtDlpManager.fetchFormats(videoUrl)
            binding.tvFetching.visibility = View.GONE
            result.onSuccess { formatsResult ->
                formatsClient = formatsResult.clientUsed
                // Build ONE row list: section headers + entries.
                val rows = mutableListOf<FormatRow>()
                rows.add(FormatRow.Section("🎵 " + getString(com.phoenix.downloader.R.string.section_music)))
                rows.add(FormatRow.Entry(FormatInfo("MP3_128", "128K (MP3)", "~3MB", true), 0, "MP3"))
                rows.add(FormatRow.Entry(FormatInfo("MP3_192", "192K (MP3)", "~5MB", true), 0, "MP3"))
                rows.add(FormatRow.Entry(FormatInfo("MP3_320", "320K (MP3)", "~8MB", true), 0, "MP3"))
                rows.add(FormatRow.Section("🎬 " + getString(com.phoenix.downloader.R.string.section_video)))
                var firstVideoEntryIndex = -1
                // User rule: no video options below 360p. If the video ONLY
                // exists below 360p, show those rather than nothing.
                val hdFormats = formatsResult.video.filter { f ->
                    (f.label.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) >= 360
                }
                val shownFormats = hdFormats.ifEmpty { formatsResult.video }
                // Video list arrives already collapsed to ONE format per
                // quality tier (best, MP4 preferred) from YtDlpManager.
                // User rule: MP4 output only — no MKV offers.
                for (f in shownFormats) {
                    val height = f.label.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                    if (firstVideoEntryIndex < 0) firstVideoEntryIndex = rows.size
                    rows.add(FormatRow.Entry(FormatInfo(f.id, "${f.label} (MP4)", f.sizeLabel, false), height, "MP4"))
                }
                if (formatsResult.video.isEmpty()) {
                    Toast.makeText(requireContext(), "No formats found", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                val adapter = FormatListAdapter(rows) { entry ->
                    selectedFormatId = entry.info.id
                    selectedHeight = entry.height
                    selectedContainer = entry.container
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.alpha = 1f
                }
                val glm = GridLayoutManager(requireContext(), 2)
                glm.spanSizeLookup = FormatListAdapter.spanSizeLookup(adapter)
                binding.rvFormats.layoutManager = glm
                binding.rvFormats.adapter = adapter
                // Default selection: first video format (VidMate-style).
                if (firstVideoEntryIndex >= 0) {
                    adapter.preselect(firstVideoEntryIndex)
                    val first = rows[firstVideoEntryIndex] as FormatRow.Entry
                    selectedFormatId = first.info.id
                    selectedHeight = first.height
                    selectedContainer = first.container
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.alpha = 1f
                }
            }.onFailure {
                Toast.makeText(requireContext(), "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDownload() {
        // Hand the download to the engine's queue (max 3 concurrent,
        // pause/resume, survives restarts). The label is reconstructed from
        // the current selection for the Downloads screen.
        val formatLabel = when {
            selectedFormatId.startsWith("MP3_") ->
                "MP3 ${selectedFormatId.removePrefix("MP3_")}K"
            selectedHeight >= 2160 -> "4K HD (MP4)"
            selectedHeight > 0 -> "${selectedHeight}P (MP4)"
            else -> "Video (MP4)"
        }
        val item = com.phoenix.downloader.model.DownloadItem(
            id = System.currentTimeMillis(),
            title = displayTitle.ifEmpty { "Video" },
            url = videoUrl,
            formatLabel = formatLabel,
            isAudio = selectedFormatId.startsWith("MP3"),
            destPath = "",
            thumbnail = videoThumbnail,
            formatId = selectedFormatId,
            client = formatsClient,
            height = selectedHeight,
            container = selectedContainer
        )
        com.phoenix.downloader.core.DownloadEngine.enqueue(requireContext(), item)
        dismiss()
        Toast.makeText(requireContext(), "Downloading: ${item.title}", Toast.LENGTH_SHORT).show()
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
