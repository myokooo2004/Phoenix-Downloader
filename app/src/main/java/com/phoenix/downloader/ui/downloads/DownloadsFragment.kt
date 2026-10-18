package com.phoenix.downloader.ui.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phoenix.downloader.R
import com.phoenix.downloader.databinding.FragmentDownloadsBinding
import java.io.File

class DownloadsFragment : Fragment() {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        
        val dir = requireContext().getExternalFilesDir(null)
        val files = dir?.listFiles()?.filter { 
            it.extension == "mp4" || it.extension == "mp3" || it.extension == "m4a" 
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvDownloads.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvDownloads.visibility = View.VISIBLE
            binding.rvDownloads.adapter = FileAdapter(files)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class FileAdapter(private val files: List<File>) : RecyclerView.Adapter<FileAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tv_file_name)
        val tvMeta: TextView = v.findViewById(R.id.tv_file_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = files[position]
        holder.tvName.text = file.name
        val sizeMb = file.length() / (1024 * 1024)
        val sizeKb = file.length() / 1024
        holder.tvMeta.text = if (sizeMb > 0) "$sizeMb MB" else "$sizeKb KB"
    }
    override fun getItemCount() = files.size
}
