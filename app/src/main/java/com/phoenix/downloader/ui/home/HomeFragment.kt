package com.phoenix.downloader.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        // Hide the search bar on scroll down, show on scroll up. The bar is
        // an OVERLAY (see fragment_home.xml): hiding slides it up with zero
        // effect on the list's layout. Frame analysis of a user recording
        // (2026-10-20) proved the old in-flow GONE/VISIBLE toggles were the
        // scroll jank: 14 toggles in 11s, each shifting content ~66dp and
        // stalling frames. Rules now: flip only after ~90dp accumulated in
        // one direction, at most once per 600ms, always show at the top.
        var searchBarVisible = true
        var scrollAccum = 0
        var lastBarToggleAt = 0L

        fun applySearchBar(show: Boolean) {
            if (show == searchBarVisible) return
            searchBarVisible = show
            lastBarToggleAt = android.os.SystemClock.uptimeMillis()
            val bar = binding.searchBarContainer
            if (show) {
                bar.visibility = View.VISIBLE
                bar.animate().translationY(0f).setDuration(180).start()
            } else {
                val lp = bar.layoutParams as android.widget.FrameLayout.LayoutParams
                val dist = (bar.height + lp.topMargin).toFloat()
                bar.animate().translationY(-dist).setDuration(180)
                    .withEndAction { if (!searchBarVisible) bar.visibility = View.INVISIBLE }
                    .start()
            }
        }
        // + Infinite scroll: load more when reaching bottom
        binding.rvVideos.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(-1)) {
                    // At the very top the bar is always shown.
                    scrollAccum = 0
                    applySearchBar(true)
                } else {
                    if (dy > 0) {
                        if (scrollAccum < 0) scrollAccum = 0
                        scrollAccum += dy
                    } else if (dy < 0) {
                        if (scrollAccum > 0) scrollAccum = 0
                        scrollAccum += dy
                    }
                    val desired = when {
                        scrollAccum > 90 -> false
                        scrollAccum < -90 -> true
                        else -> searchBarVisible
                    }
                    if (desired != searchBarVisible &&
                        android.os.SystemClock.uptimeMillis() - lastBarToggleAt >= 600
                    ) {
                        applySearchBar(desired)
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

        // Instant first paint (user: home videos appear too slowly):
        // render the disk-cached trending list from the last session, or
        // the bundled snapshot on first install. loadTrendingData() then
        // refreshes from the network in the background.
        if (videoList.isEmpty()) {
            videoList.addAll(
                com.phoenix.downloader.core.TrendingCache.load(requireContext())
                    .ifEmpty { loadBundledTrending() }
            )
        }
        // A retained fragment returning from the back stack keeps its
        // videoList but gets a NEW RecyclerView: the adapter must be
        // (re)attached here. onDestroyView drops the stale adapter —
        // without this, coming back to Home showed a blank list.
        if (videoList.isNotEmpty()) ensureAdapter()

        loadTrendingData()
        
        // The Home bar is a doorway, not a text field: tapping it opens
        // the dedicated search screen (history + hot searches + live
        // suggestions).
        binding.etSearch.setOnClickListener {
            (activity as? MainActivity)?.openSearchEntry()
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

    private fun ensureAdapter() {
        if (videoAdapter != null) return
        videoAdapter = VideoAdapter(videoList,
            onWatchClick = { video -> (activity as MainActivity).openVideoDetail(video.url, video.title, video.thumbnail, video.duration) },
            onDownloadClick = { video -> DownloadDialogFragment.newInstance(video.url, video.title, video.thumbnail, video.duration).show(childFragmentManager, "DownloadDialog") }
        )
        binding.rvVideos.adapter = videoAdapter
    }

    private fun loadTrendingData() {
        if (isLoading) return
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            // Live first (user direction 2026-10-22): Home = YouTube's
            // own feed, fetched directly from the phone (getTrending
            // tries the direct path, then the Worker). The GitHub mirror
            // below is only a last-resort filler when both live paths
            // fail — never the primary source.
            val result = YouTubeApiClient.getTrending("MM", 20, null)
            if (result.isSuccess) {
                val (videos, token) = result.getOrNull() ?: Pair(emptyList(), null)
                if (videos.isNotEmpty()) {
                    isLoading = false
                    applyFirstPage(videos, token)
                    return@launch
                }
            }
            val feed = YouTubeApiClient.fetchTrendingFeed()
            if (feed.isNotEmpty()) {
                isLoading = false
                applyFirstPage(feed, null)
                return@launch
            }
            isLoading = false
            if (videoList.isEmpty()) {
                videoList.addAll(loadBundledTrending())
                if (videoList.isNotEmpty()) ensureAdapter()
            }
            if (videoList.isEmpty()) {
                Toast.makeText(requireContext(), "Data မရပါ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Cache + paint a fresh first page, unless the user is mid-scroll. */
    private fun applyFirstPage(videos: List<VideoItem>, token: String?) {
        context?.let { com.phoenix.downloader.core.TrendingCache.save(it, videos) }
        if (binding.rvVideos.computeVerticalScrollOffset() > 0) {
            // The user is already scrolling through the cached list:
            // swapping content under their finger would yank the scroll
            // position. The fresh list is saved to disk and appears on
            // the next app open instead.
            return
        }
        nextPageToken = token
        videoList.clear()
        videoList.addAll(videos)
        if (videoAdapter == null) ensureAdapter()
        else videoAdapter?.notifyDataSetChanged()
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
                val seen = videoList.map { it.url }.toHashSet()
                val fresh = videos.filter { it.url !in seen }
                val startPos = videoList.size
                videoList.addAll(fresh)
                if (fresh.isNotEmpty()) {
                    videoAdapter?.notifyItemRangeInserted(startPos, fresh.size)
                    context?.let { com.phoenix.downloader.core.TrendingCache.save(it, videoList) }
                }
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
        // The adapter belongs to the destroyed RecyclerView, and a fetch
        // cancelled with the view never resets isLoading: clear both, or
        // a back-stack return leaves Home blank and unrefreshable.
        videoAdapter = null
        isLoading = false
    }
}
