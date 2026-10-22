package com.phoenix.downloader.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.core.YouTubeApiClient
import com.phoenix.downloader.databinding.FragmentSearchBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import com.phoenix.downloader.ui.home.VideoAdapter
import com.phoenix.downloader.ui.home.VideoItem
import kotlinx.coroutines.launch

/**
 * Search results screen: type in the Home search bar -> results like the
 * reference apps (thumbnail, title, duration, per-item download button),
 * infinite scroll via the Worker's /search pagination.
 */
class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    // Header auto-hide (user spec, same rule as the home browser bar):
    // scrolling up through results hides the query/downloads header,
    // scrolling back down toward the top brings it back.
    private var headerAnimator: android.animation.ValueAnimator? = null
    private var headerFullHeight = -1
    private var headerHidden = false
    private var headerDownAcc = 0
    private var headerUpAcc = 0

    private var query = ""
    private val items = mutableListOf<VideoItem>()
    private lateinit var adapter: VideoAdapter
    private var nextToken: String? = null
    private var loading = false
    private var firstLoadDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = arguments?.getString("QUERY") ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvSearchQuery.text = query
        binding.btnSearchDownloads.setOnClickListener { (activity as? MainActivity)?.showDownloads() }

        adapter = VideoAdapter(
            videos = items,
            onWatchClick = { video ->
                (activity as? MainActivity)?.openBrowser(video.url)
            },
            onDownloadClick = { video ->
                DownloadDialogFragment.newInstance(video.url, video.title, video.thumbnail)
                    .show(childFragmentManager, "DownloadDialog")
            }
        )
        binding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchResults.adapter = adapter

        binding.rvSearchResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= items.size - 5) loadMore()
            }
        })

        // Header auto-hide on scroll (separate listener; the paging
        // listener above is untouched).
        binding.rvSearchResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                when {
                    !rv.canScrollVertically(-1) -> {
                        headerDownAcc = 0
                        headerUpAcc = 0
                        showSearchHeader()
                    }
                    dy > 0 -> {
                        headerDownAcc += dy
                        headerUpAcc = 0
                        if (headerDownAcc > 90) hideSearchHeader()
                    }
                    dy < 0 -> {
                        headerUpAcc += -dy
                        headerDownAcc = 0
                        if (headerUpAcc > 45) showSearchHeader()
                    }
                }
            }
        })

        loadMore()
    }

    private fun hideSearchHeader() {
        if (_binding == null || headerHidden) return
        headerHidden = true
        val wrap = binding.searchHeaderWrap
        if (wrap.height > 0) headerFullHeight = wrap.height
        animateHeaderHeight(wrap.height, 0) { wrap.visibility = View.GONE }
    }

    private fun showSearchHeader() {
        if (_binding == null || !headerHidden) return
        headerHidden = false
        val wrap = binding.searchHeaderWrap
        wrap.visibility = View.VISIBLE
        val target = if (headerFullHeight > 0) headerFullHeight else {
            wrap.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            return
        }
        animateHeaderHeight(0, target) {
            wrap.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            wrap.requestLayout()
        }
    }

    private fun animateHeaderHeight(from: Int, to: Int, onEnd: () -> Unit) {
        if (_binding == null) return
        val wrap = binding.searchHeaderWrap
        headerAnimator?.cancel()
        if (from == to) {
            onEnd()
            return
        }
        headerAnimator = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 160
            addUpdateListener { anim ->
                if (_binding == null) return@addUpdateListener
                val lp = wrap.layoutParams
                lp.height = anim.animatedValue as Int
                wrap.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    private fun loadMore() {
        if (loading) return
        if (firstLoadDone && nextToken == null) return
        loading = true
        binding.progressSearch.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YouTubeApiClient.searchVideos(query, 20, nextToken)
            loading = false
            if (_binding == null) return@launch
            binding.progressSearch.visibility = View.GONE
            result.onSuccess { (videos, token) ->
                firstLoadDone = true
                nextToken = token
                val start = items.size
                val fresh = videos.filter { v -> items.none { it.url == v.url } }
                items.addAll(fresh)
                if (fresh.isNotEmpty()) adapter.notifyItemRangeInserted(start, fresh.size)
                binding.tvSearchEmpty.visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure { e ->
                if (items.isEmpty()) {
                    binding.tvSearchEmpty.visibility = View.VISIBLE
                    binding.tvSearchEmpty.text = "Search failed: ${e.message}"
                } else {
                    Toast.makeText(requireContext(), "Load failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(query: String) = SearchFragment().apply {
            arguments = Bundle().apply { putString("QUERY", query) }
        }
    }
}
