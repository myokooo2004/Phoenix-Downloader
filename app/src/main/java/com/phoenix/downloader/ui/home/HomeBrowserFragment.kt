package com.phoenix.downloader.ui.home

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.databinding.FragmentHomeBrowserBinding
import com.phoenix.downloader.ui.browser.BrowserFragment

/**
 * Home, browser base (user spec 2026-10-22, mockups v4-v6): the top
 * bar is OURS (a "Search or enter url" doorway pill + the downloads
 * button at its side); everything below it is YouTube itself in the
 * in-app browser (home-mode: no browser chrome of its own), with our
 * overlay injected into the page (per-item download buttons, the
 * watch-page DOWNLOAD pill, hidden YouTube header, rounded
 * thumbnails, view-count badges). No native feed, no cache list.
 */
class HomeBrowserFragment : Fragment() {

    private var _binding: FragmentHomeBrowserBinding? = null
    private val binding get() = _binding!!

    // Top-bar auto-hide (user spec 2026-10-22): on feed/results the
    // bar hides while scrolling down and returns on scroll up; on a
    // watch page it hides the moment the video opens and stays
    // hidden, so the video sits at the very top, full-bleed.
    private var barAnimator: ValueAnimator? = null
    private var barFullHeight = -1
    private var barHidden = false
    private var onWatchPage = false
    private var lastScrollY = 0
    private var downAcc = 0
    private var upAcc = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The bar is a doorway, not a text field: tapping it opens the
        // search entry screen (history + hot searches + YouTube
        // suggestions), exactly as the old Home bar did.
        binding.etSearch.setOnClickListener {
            (activity as? MainActivity)?.openSearchEntry()
        }

        // Downloads button: opens My Files / Downloads.
        binding.btnOpenDownloads.setOnClickListener {
            (activity as? MainActivity)?.showDownloads()
        }
        // Long-press downloads button copies the crash log (the field
        // diagnostics channel — same as the old Home).
        binding.btnOpenDownloads.setOnLongClickListener {
            val log = com.phoenix.downloader.core.CrashLogger.getLog(requireContext())
            if (log != null) {
                val truncated = if (log.length > 100_000) {
                    "...[truncated, showing last 100KB]...\n" + log.takeLast(100_000)
                } else log
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("crash_log", truncated))
                Toast.makeText(requireContext(), "Crash log copied", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No crashes logged", Toast.LENGTH_SHORT).show()
            }
            true
        }

        if (childFragmentManager.findFragmentById(R.id.home_browser_container) == null) {
            val startUrl = arguments?.getString(ARG_START_URL)
                ?.takeIf { it.isNotEmpty() } ?: YOUTUBE_HOME
            childFragmentManager.beginTransaction()
                .add(
                    R.id.home_browser_container,
                    BrowserFragment.newInstance(startUrl, homeMode = true),
                    BROWSER_TAG
                )
                .commitNow()
        }

        // Hook the embedded browser for the top-bar behavior, and
        // evaluate the page we are already on (restored state).
        val browser = childFragmentManager.findFragmentByTag(BROWSER_TAG) as? BrowserFragment
        browser?.onHostUrlChanged = { url -> onBrowserUrl(url) }
        browser?.onHostScroll = { y -> onBrowserScroll(y) }
        val initialUrl = browser?.currentUrl()
            ?: arguments?.getString(ARG_START_URL)?.takeIf { it.isNotEmpty() }
            ?: YOUTUBE_HOME
        onBrowserUrl(initialUrl)
    }

    private fun onBrowserUrl(url: String) {
        val watch = url.contains("/watch") || url.contains("youtu.be/") ||
            url.contains("/shorts/")
        onWatchPage = watch
        downAcc = 0
        upAcc = 0
        lastScrollY = 0
        if (watch) hideBar() else showBar()
    }

    private fun onBrowserScroll(y: Int) {
        if (onWatchPage) return // the bar stays hidden while watching
        val dy = y - lastScrollY
        lastScrollY = y
        when {
            y <= 0 -> {
                downAcc = 0
                upAcc = 0
                showBar()
            }
            dy > 0 -> {
                downAcc += dy
                upAcc = 0
                if (downAcc > 90) hideBar()
            }
            dy < 0 -> {
                upAcc += -dy
                downAcc = 0
                if (upAcc > 45) showBar()
            }
        }
    }

    private fun hideBar() {
        if (_binding == null || barHidden) return
        barHidden = true
        val wrap = binding.topBarWrap
        if (wrap.height > 0) barFullHeight = wrap.height
        animateBarHeight(wrap.height, 0) { wrap.visibility = View.GONE }
    }

    private fun showBar() {
        if (_binding == null || !barHidden) return
        barHidden = false
        val wrap = binding.topBarWrap
        wrap.visibility = View.VISIBLE
        val target = if (barFullHeight > 0) barFullHeight else {
            wrap.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            return
        }
        animateBarHeight(0, target) {
            wrap.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            wrap.requestLayout()
        }
    }

    private fun animateBarHeight(from: Int, to: Int, onEnd: () -> Unit) {
        if (_binding == null) return
        val wrap = binding.topBarWrap
        barAnimator?.cancel()
        if (from == to) {
            onEnd()
            return
        }
        barAnimator = ValueAnimator.ofInt(from, to).apply {
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

    /** The embedded browser's current url (for search-field sync). */
    fun currentUrl(): String? {
        val browser = childFragmentManager.findFragmentByTag(BROWSER_TAG) as? BrowserFragment
        return browser?.currentUrl()
    }

    /** Load a url in the embedded browser (search submits land here). */
    fun navigate(url: String) {
        val browser = childFragmentManager.findFragmentByTag(BROWSER_TAG) as? BrowserFragment
        browser?.navigate(url)
    }

    override fun onDestroyView() {
        barAnimator?.cancel()
        barAnimator = null
        val browser = childFragmentManager.findFragmentByTag(BROWSER_TAG) as? BrowserFragment
        browser?.onHostUrlChanged = null
        browser?.onHostScroll = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_START_URL = "start_url"
        private const val BROWSER_TAG = "home_browser"
        const val YOUTUBE_HOME = "https://m.youtube.com/"

        fun newInstance(startUrl: String? = null) = HomeBrowserFragment().apply {
            arguments = Bundle().apply {
                if (!startUrl.isNullOrEmpty()) putString(ARG_START_URL, startUrl)
            }
        }
    }
}
