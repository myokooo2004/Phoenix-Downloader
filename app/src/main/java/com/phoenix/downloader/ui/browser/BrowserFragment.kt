package com.phoenix.downloader.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.core.UrlDetector
import com.phoenix.downloader.core.YtDlpManager
import com.phoenix.downloader.databinding.FragmentBrowserBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import kotlinx.coroutines.launch

/**
 * Full in-app browser (mockup 5): back / forward / refresh, URL bar with
 * lock icon, page progress. Standard WebView TLS handling — no custom
 * SSL bypass, so no security errors are shown.
 *
 * When navigation lands on a video URL, the floating download button
 * appears; tapping it fetches the video info and opens the download dialog.
 * Account logins work because this is a real WebView with cookies enabled.
 */
class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private var pendingVideoUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val wv = binding.webview
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        // Hide WebView indicator so sites like TikTok don't block/redirect
        wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
        // Default TLS handling (no onReceivedSslError override) -> no security errors.

        // JavaScript bridge for per-video download buttons (Vidmate-style)
        wv.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onDownloadClick(videoUrl: String) {
                activity?.runOnUiThread {
                    viewLifecycleOwner.lifecycleScope.launch {
                        YtDlpManager.fetchVideoInfo(videoUrl)
                            .onSuccess { DownloadDialogFragment.newInstance(it.url, it.title).show(parentFragmentManager, "DownloadDialog") }
                            .onFailure { 
                                context?.let { ctx ->
                                    android.widget.Toast.makeText(
                                        ctx, 
                                        it.message ?: "Could not fetch video", 
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                    }
                }
            }
        }, "PhoenixDownloader")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false // stay inside this WebView
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                binding.tvUrl.text = url
                checkForVideo(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // SPA sites (YouTube/Facebook) update URL via JS; re-check on finish
                val currentUrl = view.url ?: url
                binding.tvUrl.text = currentUrl
                checkForVideo(currentUrl)
                // Save cookies for yt-dlp bot bypass (from Python file insight)
                try {
                    com.phoenix.downloader.core.CookieHelper.saveCookiesForUrl(
                        requireContext(), currentUrl
                    )
                } catch (e: Exception) { /* ignore */ }
                // Inject Vidmate-style per-video download buttons
                injectDownloadButtons(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Catches SPA pushState/replaceState navigations
                binding.tvUrl.text = url
                checkForVideo(url)
                // Re-inject per-video download buttons after SPA navigation
                injectDownloadButtons(view)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.pbPage.isVisible = newProgress in 1..99
                binding.pbPage.progress = newProgress
            }
        }

        binding.btnBack.setOnClickListener { if (wv.canGoBack()) wv.goBack() }
        binding.btnForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnRefresh.setOnClickListener { wv.reload() }
        binding.btnCloseBrowser.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnBarBack.setOnClickListener { if (wv.canGoBack()) wv.goBack() }
        binding.btnBarForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnBarHome.setOnClickListener { (activity as? MainActivity)?.showHome() }

        binding.fabDownload.setOnClickListener {
            val url = pendingVideoUrl ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                YtDlpManager.fetchVideoInfo(url)
                    .onSuccess { DownloadDialogFragment.newInstance(it.url, it.title).show(parentFragmentManager, "DownloadDialog") }
                    .onFailure {
                        context?.let { ctx ->
                            android.widget.Toast.makeText(
                                ctx,
                                it.message ?: "Could not fetch video",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
            }
        }

        val startUrl = requireArguments().getString(ARG_URL).orEmpty()
        if (savedInstanceState == null && startUrl.isNotEmpty()) {
            wv.loadUrl(startUrl)
        } else {
            wv.restoreState(savedInstanceState ?: Bundle())
        }
    }

    private fun checkForVideo(url: String) {
        val site = UrlDetector.detect(url)
        pendingVideoUrl = if (site != null) url else null
        // FAB disabled - user wants per-video buttons only, not FAB
        binding.fabDownload.isVisible = false
        binding.tvDetectHint.isVisible = pendingVideoUrl != null
    }

    /** Inject Vidmate-style download buttons next to videos in list pages */
    private fun injectDownloadButtons(webView: WebView) {
        val js = """
            (function() {
                function addButtons() {
                    var links = document.querySelectorAll('a[href*="/watch?v="]');
                    var added = 0;
                    links.forEach(function(link) {
                        var href = link.getAttribute('href');
                        if (!href) return;
                        var m = href.match(/v=([\w-]{11})/);
                        if (!m) return;
                        var vid = m[1];
                        // Attach to the video renderer container (mobile + desktop layouts),
                        // not inside the <a> (mobile anchors are unreliable click/position targets).
                        var container = link.closest('ytm-video-with-context-renderer, ytm-media-item, ytm-compact-video-renderer, ytd-rich-item-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-grid-video-renderer') || link.parentElement;
                        if (!container) return;
                        if (container.querySelector('[data-phoenix-btn]')) return;
                        var style = window.getComputedStyle(container);
                        if (style.position === 'static') {
                            container.style.position = 'relative';
                        }
                        var fullUrl = 'https://www.youtube.com/watch?v=' + vid;
                        var btn = document.createElement('div');
                        btn.setAttribute('data-phoenix-btn', '1');
                        btn.innerHTML = '⬇';
                        btn.style.cssText = 'position:absolute;top:8px;right:8px;width:36px;height:36px;' +
                            'background:#e53935;color:white;border-radius:50%;display:flex;' +
                            'align-items:center;justify-content:center;font-size:20px;z-index:2147483647;' +
                            'cursor:pointer;box-shadow:0 2px 6px rgba(0,0,0,0.4);';
                        btn.addEventListener('click', function(e) {
                            e.stopPropagation();
                            e.preventDefault();
                            PhoenixDownloader.onDownloadClick(fullUrl);
                        }, true);
                        container.appendChild(btn);
                        added++;
                    });
                    return added;
                }
                var n = addButtons();
                if (!window._phoenixObserver) {
                    var observer = new MutationObserver(function() { addButtons(); });
                    observer.observe(document.body, { childList: true, subtree: true });
                    window._phoenixObserver = observer;
                }
                return n;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                webView.context?.let { ctx ->
                    com.phoenix.downloader.core.CrashLogger.logNonFatal(
                        ctx,
                        "BrowserButtons",
                        Exception("injectDownloadButtons added=$result url=${webView.url}")
                    )
                }
            } catch (e: Exception) { /* diagnostics only */ }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.webview?.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        _binding?.webview?.onPause()
    }

    override fun onResume() {
        super.onResume()
        _binding?.webview?.onResume()
    }

    override fun onDestroyView() {
        _binding?.webview?.destroy()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_URL = "url"
        fun newInstance(url: String) = BrowserFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}
