package com.phoenix.downloader.ui.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.databinding.DialogVideoOptionsBinding

/**
 * Step 1.5 of the URL flow: tapping a video thumbnail offers
 * Watch (in-app ExoPlayer) or Download (quality bottom sheet).
 */
class VideoOptionsDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogVideoOptionsBinding? = null
    private val binding get() = _binding!!

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogVideoOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val videoUrl = requireArguments().getString(ARG_URL).orEmpty()
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        binding.tvOptTitle.text = title

        binding.btnWatch.setOnClickListener {
            dismiss()
            (activity as? MainActivity)?.openPlayerStream(videoUrl, title)
        }
        binding.btnDownloadOpt.setOnClickListener {
            dismiss()
            (activity as? MainActivity)?.openDownloadDialog(videoUrl, title)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"

        fun newInstance(videoUrl: String, title: String) = VideoOptionsDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, videoUrl)
                putString(ARG_TITLE, title)
            }
        }
    }
}
