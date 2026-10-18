package com.phoenix.downloader.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.adapter.VideoAdapter
import com.phoenix.downloader.core.UrlDetector
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.FragmentHomeBinding
import com.phoenix.downloader.model.VideoInfo
import kotlinx.coroutines.launch

/**
 * Home screen (mockup 3): search bar + downloads button, site row
 * (YouTube / Facebook / TikTok), video cards list.
 *
 * URL flow (two-step):
 *  1. Pasted video URL -> [UrlDetector] -> [YtDlpManager.fetchVideoInfo]
 *     -> video info card appears at the top of the list.
 *  2. Tap ⬇ on the card -> DownloadDialogFragment (quality bottom sheet).
 *  Tap the thumbnail -> VideoOptionsDialogFragment (Watch / Download).
 *
 * Non-URL text -> opens the browser on a YouTube search.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: VideoAdapter
    private val videos = mutableListOf<VideoInfo>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = VideoAdapter(
            onDownloadClick = { (activity as? MainActivity)?.openDownloadDialog(it.url, it.title) },
            onCardClick = { (activity as? MainActivity)?.showVideoOptions(it.url, it.title) }
        )
        binding.rvVideos.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVideos.adapter = adapter

        // Hide search bar on scroll up, show on scroll down (Vidmate-style)
        binding.rvVideos.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 10) {
                    // Scrolling up -> hide search bar
                    binding.searchBarContainer.animate().translationY(-binding.searchBarContainer.height.toFloat()).setDuration(200).start()
                } else if (dy < -10) {
                    // Scrolling down -> show search bar
                    binding.searchBarContainer.animate().translationY(0f).setDuration(200).start()
                }
            }
        })

        binding.etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handleInput(v.text.toString())
                true
            } else false
        }
        binding.btnOpenDownloads.setOnClickListener {
            (activity as? MainActivity)?.showDownloads()
        }

        binding.btnLanguage.setOnClickListener {
            toggleLanguage()
        }
        updateLangLabel()

        binding.siteYoutube.setOnClickListener {
            (activity as? MainActivity)?.openBrowser("https://www.youtube.com")
        }
        binding.siteFacebook.setOnClickListener {
            (activity as? MainActivity)?.openBrowser("https://www.facebook.com")
        }
        binding.siteTiktok.setOnClickListener {
            (activity as? MainActivity)?.openBrowser("https://www.tiktok.com")
        }

        // Deep link / shared text entry point
        arguments?.getString(ARG_SHARED_TEXT)?.let { handleInput(it) }

        // Load YouTube trending on launch (Vidmate-style home feed)
        loadTrending()
    }

    /** Load YouTube trending videos into the home feed. */
    private fun loadTrending() {
        viewLifecycleOwner.lifecycleScope.launch {
            YtDlpManager.fetchTrending()
                .onSuccess { trending ->
                    videos.clear()
                    videos.addAll(trending)
                    adapter.submitList(videos.toList())
                }
                .onFailure {
                    // Silently fail - user can still search/browse
                }
        }
    }

    /** Entry point for pasted or shared text. */
    fun handleInput(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return

        val site = UrlDetector.detect(input)
        if (site != null) {
            // Step 1: direct video URL -> fetch info, show the card first.
            fetchAndShowVideo(input)
        } else if (UrlDetector.looksLikeUrl(input)) {
            // Any other URL -> full browser.
            (activity as? MainActivity)?.openBrowser(input)
        } else {
            // Plain text -> YouTube search in the browser.
            val q = java.net.URLEncoder.encode(input, "UTF-8")
            (activity as? MainActivity)?.openBrowser("https://www.youtube.com/results?search_query=$q")
        }
    }

    private fun fetchAndShowVideo(url: String) {
        Toast.makeText(requireContext(), R.string.loading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            YtDlpManager.fetchVideoInfo(url)
                .onSuccess { info ->
                    videos.removeAll { it.url == info.url }
                    videos.add(0, info)
                    adapter.submitList(videos.toList())
                    binding.rvVideos.scrollToPosition(0)
                }
                .onFailure {
                    Toast.makeText(requireContext(), R.string.invalid_link, Toast.LENGTH_LONG).show()
                }
        }
    }

    /** Toggle between English and Myanmar, then recreate activity. */
    private fun toggleLanguage() {
        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val current = prefs.getString("language", "en") ?: "en"
        val newLang = if (current == "en") "my" else "en"
        prefs.edit().putString("language", newLang).apply()

        // Apply the new locale
        val locale = java.util.Locale(newLang)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        // Recreate to apply
        requireActivity().recreate()
    }

    /** Update the language button label (မြန် for Myanmar, EN for English). */
    private fun updateLangLabel() {
        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val current = prefs.getString("language", "en") ?: "en"
        binding.tvLangLabel.text = if (current == "en") "မြန်" else "EN"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SHARED_TEXT = "shared_text"

        fun newInstance(sharedText: String? = null) = HomeFragment().apply {
            arguments = Bundle().apply { putString(ARG_SHARED_TEXT, sharedText) }
        }
    }
}
