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
