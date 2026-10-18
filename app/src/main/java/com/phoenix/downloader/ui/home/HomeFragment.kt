package com.phoenix.downloader.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.databinding.FragmentHomeBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import org.json.JSONObject

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvVideos.layoutManager = LinearLayoutManager(requireContext())
        
        loadTrendingData()
        
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val url = binding.etSearch.text.toString().trim()
                if (url.isNotEmpty()) {
                    DownloadDialogFragment.newInstance(url, "Download").show(childFragmentManager, "DownloadDialog")
                    binding.etSearch.setText("")
                }
                true
            } else false
        }

        // Downloads button
        binding.btnOpenDownloads.setOnClickListener {
            (activity as? MainActivity)?.showDownloads()
        }

        // Language button: tap to toggle, long-press to copy crash log
        binding.btnLanguage.setOnClickListener {
            toggleLanguage()
        }
        binding.btnLanguage.setOnLongClickListener {
            val log = com.phoenix.downloader.core.CrashLogger.getLog(requireContext())
            if (log != null) {
                val truncated = if (log.length > 100_000) {
                    "...[truncated, showing last 100KB]...\n" + log.takeLast(100_000)
                } else log
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash_log", truncated))
                Toast.makeText(requireContext(), "Crash log copied", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No crashes logged", Toast.LENGTH_SHORT).show()
            }
            true
        }
        updateLangLabel()

        // Site buttons: open in-app browser
        binding.siteYoutube.setOnClickListener {
            (activity as? MainActivity)?.openBrowser("https://www.youtube.com")
        }
        binding.siteFacebook.setOnClickListener {
            (activity as? MainActivity)?.openBrowser("https://www.facebook.com")
        }
        binding.siteTiktok.setOnClickListener {
            (activity as? MainActivity)?.openBrowser("https://www.tiktok.com")
        }
    }

    private fun toggleLanguage() {
        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val current = prefs.getString("language", "en") ?: "en"
        val newLang = if (current == "en") "my" else "en"
        prefs.edit().putString("language", newLang).apply()
        updateLangLabel()
        requireActivity().recreate()
    }

    private fun updateLangLabel() {
        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val current = prefs.getString("language", "en") ?: "en"
        // Show "မြန်" when current is EN, "EN" when current is MY
        binding.btnLanguage.text = if (current == "en") "မြန်" else "EN"
    }

    private fun loadTrendingData() {
        try {
            val jsonString = requireContext().assets.open("trending.json").bufferedReader().use { it.readText() }
            val videosArray = JSONObject(jsonString).getJSONArray("videos")
            val videoList = mutableListOf<VideoItem>()
            for (i in 0 until videosArray.length()) {
                val obj = videosArray.getJSONObject(i)
                videoList.add(VideoItem(obj.getString("url"), obj.getString("title"), obj.getString("thumbnailUrl"), obj.getInt("duration")))
            }
            binding.rvVideos.adapter = VideoAdapter(videoList, 
                onWatchClick = { video -> (activity as MainActivity).openPlayer(video.url, video.title) },
                onDownloadClick = { video -> DownloadDialogFragment.newInstance(video.url, video.title).show(childFragmentManager, "DownloadDialog") }
            )
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Data မရပါ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
