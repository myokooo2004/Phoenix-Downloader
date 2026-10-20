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
        binding.btnSearchBack.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
        binding.btnSearchDownloads.setOnClickListener { (activity as? MainActivity)?.showDownloads() }

        adapter = VideoAdapter(
            videos = items,
            onWatchClick = { video ->
                (activity as? MainActivity)?.openVideoDetail(video.url, video.title, video.thumbnail, video.duration)
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

        loadMore()
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
