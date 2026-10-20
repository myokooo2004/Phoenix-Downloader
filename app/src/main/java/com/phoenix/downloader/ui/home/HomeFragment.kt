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
import com.phoenix.downloader.core.YouTubeApiClient
import com.phoenix.downloader.databinding.FragmentHomeBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import kotlinx.coroutines.launch
import org.json.JSONObject

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val videoList = mutableListOf<VideoItem>()
    private var nextPageToken: String? = null
    private var isLoading = false
    private var videoAdapter: VideoAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvVideos.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVideos.setHasFixedSize(true)

        // Hide search bar on scroll down, show on scroll up (Vidmate-style).
        // Hysteresis: toggling visibility forces a full layout pass, so only
        // flip after ~60dp of accumulated scroll in ONE direction (and only
        // when the state actually changes) - per-event flips made scrolling
        // visibly jerky.
        var searchBarVisible = true
        var scrollAccum = 0
        // + Infinite scroll: load more when reaching bottom
        binding.rvVideos.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) {
                    if (scrollAccum < 0) scrollAccum = 0
                    scrollAccum += dy
                    if (searchBarVisible && scrollAccum > 60) {
                        searchBarVisible = false
                        binding.searchBarContainer.visibility = View.GONE
                        scrollAccum = 0
                    }
                } else if (dy < 0) {
                    if (scrollAccum > 0) scrollAccum = 0
                    scrollAccum += dy
                    if (!searchBarVisible && scrollAccum < -60) {
                        searchBarVisible = true
                        binding.searchBarContainer.visibility = View.VISIBLE
                        scrollAccum = 0
                    }
                }

                // Infinite scroll
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (!isLoading && nextPageToken != null && lastVisibleItem >= totalItemCount - 5) {
                    loadMoreTrending()
                }
            }
        })

        loadTrendingData()
        
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val text = binding.etSearch.text.toString().trim()
                if (text.isNotEmpty()) {
                    val looksLikeUrl = text.startsWith("http://") || text.startsWith("https://") ||
                        text.contains("youtube.com") || text.contains("youtu.be") ||
                        text.contains("facebook.com") || text.contains("fb.watch") ||
                        text.contains("tiktok.com")
                    if (looksLikeUrl) {
                        // Pasted link -> download dialog (existing URL flow)
                        val url = if (text.startsWith("http")) text else "https://$text"
                        DownloadDialogFragment.newInstance(url, "Download").show(childFragmentManager, "DownloadDialog")
                    } else {
                        // Plain text -> search results screen
                        (activity as? MainActivity)?.openSearch(text)
                    }
                    binding.etSearch.setText("")
                }
                true
            } else false
        }

        // Downloads button
        binding.btnOpenDownloads.setOnClickListener {
            (activity as? MainActivity)?.showDownloads()
        }
        // Long-press downloads button to copy crash log
        binding.btnOpenDownloads.setOnLongClickListener {
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

    private fun loadTrendingData() {
        if (isLoading) return
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            // Try YouTube Data API first (real-time), fallback to bundled assets
            val result = YouTubeApiClient.getTrending("MM", 20, null)
            isLoading = false
            if (result.isSuccess) {
                val (videos, token) = result.getOrNull() ?: Pair(emptyList(), null)
                nextPageToken = token
                videoList.clear()
                videoList.addAll(videos)
            } else {
                // Fallback to bundled trending.json
                videoList.clear()
                videoList.addAll(loadBundledTrending())
                nextPageToken = null
            }
            if (videoList.isEmpty()) {
                Toast.makeText(requireContext(), "Data မရပါ", Toast.LENGTH_SHORT).show()
                return@launch
            }
            videoAdapter = VideoAdapter(videoList,
                onWatchClick = { video -> (activity as MainActivity).openVideoDetail(video.url, video.title, video.thumbnail, video.duration) },
                onDownloadClick = { video -> DownloadDialogFragment.newInstance(video.url, video.title, video.thumbnail, video.duration).show(childFragmentManager, "DownloadDialog") }
            )
            binding.rvVideos.adapter = videoAdapter
        }
    }

    private fun loadMoreTrending() {
        val token = nextPageToken ?: return
        if (isLoading) return
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YouTubeApiClient.getTrending("MM", 20, token)
            isLoading = false
            if (result.isSuccess) {
                val (videos, newToken) = result.getOrNull() ?: Pair(emptyList(), null)
                nextPageToken = newToken
                val startPos = videoList.size
                videoList.addAll(videos)
                videoAdapter?.notifyItemRangeInserted(startPos, videos.size)
            }
        }
    }

    private fun loadBundledTrending(): List<VideoItem> {
        return try {
            val jsonString = requireContext().assets.open("trending.json").bufferedReader().use { it.readText() }
            val videosArray = JSONObject(jsonString).getJSONArray("videos")
            val videoList = mutableListOf<VideoItem>()
            for (i in 0 until videosArray.length()) {
                val obj = videosArray.getJSONObject(i)
                videoList.add(VideoItem(obj.getString("url"), obj.getString("title"), obj.getString("thumbnailUrl"), obj.getInt("duration")))
            }
            videoList
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
