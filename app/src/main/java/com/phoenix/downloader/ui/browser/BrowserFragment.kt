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

    // Video-tap interception state (see shouldOverrideUrlLoading): the
    // in-flight info fetch (self-clears when the view is destroyed, since
    // it runs in the view lifecycle scope) and the last intercepted URL.
    private var detailFetchJob: kotlinx.coroutines.Job? = null
    private var lastInterceptUrl: String? = null
    private var lastInterceptAt: Long = 0L

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

            @android.webkit.JavascriptInterface
            fun reportDiag(json: String) {
                try {
                    val ctx = activity?.applicationContext ?: return
                    com.phoenix.downloader.core.CrashLogger.logNonFatal(
                        ctx, "BrowserButtons.final", Exception(json)
                    )
                } catch (e: Exception) { /* diagnostics only */ }
            }
        }, "PhoenixDownloader")

        // "Video detected" hint: tap -> open the detail page (player +
        // related), same as tapping a video on Home (user spec 2026-10-20).
        binding.tvDetectHint.setOnClickListener {
            val url = pendingVideoUrl ?: return@setOnClickListener
            openDetailFor(url) { e ->
                android.widget.Toast.makeText(
                    requireContext(),
                    e.message ?: "Could not fetch video",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // VidMate-style entry (user, 2026-10-21): a TAP on a video
                // link opens our detail page (pinned player + red Download)
                // instead of loading the site's watch page, which has no
                // download button at all. Stability guards, so the page
                // never "jumps out" on its own:
                //  - main-frame + real user gesture only; redirects and
                //    SPA pushState navigations are NOT intercepted
                //    (doUpdateVisitedHistory is deliberately not hooked,
                //    so in-page autoplay/next never yanks the user out)
                //  - only URLs UrlDetector knows are video pages
                //  - one info fetch in flight at a time; the same URL is
                //    latched for 3s so a double-fire opens one page only
                //  - fetch failure -> the WebView loads the page as before
                val target = request.url?.toString() ?: return false
                if (!request.isForMainFrame || !request.hasGesture()) return false
                if (UrlDetector.detect(target) == null) return false
                if (target == view.url) return false
                if (detailFetchJob?.isActive == true) return true
                val now = android.os.SystemClock.uptimeMillis()
                if (target == lastInterceptUrl && now - lastInterceptAt < 3000) return true
                lastInterceptUrl = target
                lastInterceptAt = now
                openDetailFor(target) { view.loadUrl(target) }
                return true
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

        binding.btnForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnRefresh.setOnClickListener { wv.reload() }
        binding.btnCloseBrowser.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnBarForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnBarHome.setOnClickListener { (activity as? MainActivity)?.showHome() }

        // No on-screen back buttons anywhere (user rule, 2026-10-20): the
        // PHONE back key walks the WebView history first; at the start of
        // the history it hands over to the activity callback, which pops
        // the browser.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (wv.canGoBack()) {
                        wv.goBack()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

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

    /**
     * Shared path for the "Video detected" pill and for video-link tap
     * interception: fetch the video's info, then open the detail page
     * (pinned player + red Download + related). [onFetchFail] decides
     * what a failure means for each entry point.
     */
    private fun openDetailFor(url: String, onFetchFail: (Throwable) -> Unit) {
        detailFetchJob = viewLifecycleOwner.lifecycleScope.launch {
            YtDlpManager.fetchVideoInfo(url)
                .onSuccess { info ->
                    (activity as? MainActivity)?.openVideoDetail(
                        info.url, info.title,
                        info.thumbnailUrl ?: "", info.durationSec.toInt()
                    )
                }
                .onFailure { e -> onFetchFail(e) }
        }
    }

    private fun checkForVideo(url: String) {
        val site = UrlDetector.detect(url)
        pendingVideoUrl = if (site != null) url else null
        // FAB disabled - user wants per-video buttons only, not FAB
        binding.fabDownload.isVisible = false
        binding.tvDetectHint.isVisible = pendingVideoUrl != null
        // Pre-warm formats for the detected watch page (dialog opens fast).
        if (pendingVideoUrl != null) YtDlpManager.prewarmFormats(url)
    }

    /** Inject a small download icon into each video item (user style). */
    private fun injectDownloadButtons(webView: WebView) {
        // NOTE: m.youtube.com renders video lists via JS after onPageFinished.
        // This script retries on a timer + MutationObserver, and returns JSON
        // diagnostics so the crash log shows exactly what the DOM contained.
        //
        // Placement/style (user, 2026-10-20, from their reference screenshot):
        // a plain outline ⬇ icon at the bottom-right of each video
        // (enlarged 2026-10-21 per user — 40px box, 24px glyph; it read
        // as too small in the field)
        // ITEM (next to the item's ⋮ menu) — NOT a red circle floating on
        // the thumbnail, and never on the player/header. The host is the
        // list-item renderer itself (no parent fallback), it must contain
        // a thumbnail image, and the button scrolls away with its item.
        // YouTube recycles rows while scrolling, so the button stores its
        // video id in data-vid and each scan refreshes it.
        val js = """
            (function() {
                try {
                    var debug = {anchors: 0, matched: 0, added: 0, refreshed: 0, noThumb: 0, inPlayer: 0, vis: null, err: null};
                    var stats = window._phxStats || (window._phxStats = {added: 0, refreshed: 0, passes: 0});
                    var reported = false;
                    function probeVis(btn) {
                        try {
                            var r = btn.getBoundingClientRect();
                            return (btn.offsetParent ? 'p' : 'np') + '|' + Math.round(r.width) + 'x' + Math.round(r.height) + '|t' + Math.round(r.top);
                        } catch (e) { return 'probe-err'; }
                    }
                    function reportOnce() {
                        if (reported) return;
                        reported = true;
                        try {
                            if (window.PhoenixDownloader && PhoenixDownloader.reportDiag) {
                                PhoenixDownloader.reportDiag(JSON.stringify({stats: stats, last: debug, href: location.href}));
                            }
                        } catch (e) {}
                    }
                    var RENDERERS = 'ytm-video-with-context-renderer,ytm-media-item,ytm-compact-video-renderer,ytm-playlist-video-renderer,ytm-playlist-panel-video-renderer,ytd-rich-item-renderer,ytd-video-renderer,ytd-compact-video-renderer,ytd-grid-video-renderer,ytd-playlist-panel-video-renderer,ytd-playlist-video-renderer';
                    function videoIdOf(a) {
                        var href = a.getAttribute('href') || '';
                        var m = href.match(/[?&]v=([\w-]{11})/);
                        return m ? m[1] : null;
                    }
                    function addButtons() {
                        stats.passes++;
                        debug.added = 0; debug.refreshed = 0;
                        try {
                            var links = document.querySelectorAll('a[href*="watch"]');
                            debug.anchors = links.length;
                            for (var i = 0; i < links.length; i++) {
                                var link = links[i];
                                var vid = videoIdOf(link);
                                if (!vid) continue;
                                debug.matched++;
                                var host = link.closest(RENDERERS);
                                if (!host) continue;
                                if (!host.querySelector('img')) { debug.noThumb++; continue; }
                                if (host.closest('ytm-player,#player,.html5-video-player,ytm-inline-player')) {
                                    debug.inPlayer++; continue;
                                }
                                var existing = host.querySelector('[data-phoenix-btn]');
                                if (existing) {
                                    // Recycled row: refresh the target video
                                    existing.setAttribute('data-vid', vid);
                                    debug.refreshed++; stats.refreshed++;
                                    if (!debug.vis) debug.vis = probeVis(existing);
                                    continue;
                                }
                                try {
                                    if (window.getComputedStyle(host).position === 'static') {
                                        host.style.position = 'relative';
                                    }
                                } catch (e2) {}
                                var btn = document.createElement('div');
                                btn.setAttribute('data-phoenix-btn', '1');
                                btn.setAttribute('data-vid', vid);
                                btn.textContent = '⬇';
                                btn.style.cssText = 'position:absolute;right:30px;bottom:4px;width:40px;height:40px;' +
                                    'color:#616161;display:flex;align-items:center;justify-content:center;' +
                                    'font-size:24px;z-index:9999;cursor:pointer;background:transparent;';
                                btn.addEventListener('click', function(e) {
                                    e.stopPropagation();
                                    e.preventDefault();
                                    var t = e.currentTarget;
                                    var v = t ? t.getAttribute('data-vid') : null;
                                    if (v) PhoenixDownloader.onDownloadClick('https://www.youtube.com/watch?v=' + v);
                                }, true);
                                host.appendChild(btn);
                                debug.added++; stats.added++;
                                if (!debug.vis) debug.vis = probeVis(btn);
                            }
                        } catch (e) { debug.err = String(e && e.message || e); }
                        return debug;
                    }
                    var d = addButtons();
                    var tries = 0;
                    var timer = setInterval(function() {
                        tries++;
                        addButtons();
                        if (tries >= 10) { clearInterval(timer); reportOnce(); }
                    }, 1000);
                    setTimeout(reportOnce, 25000);
                    if (!window._phoenixObserver && document.body) {
                        try {
                            window._phoenixObserver = new MutationObserver(function() { addButtons(); });
                            window._phoenixObserver.observe(document.body, { childList: true, subtree: true });
                        } catch (e3) { debug.err = 'observer: ' + e3; }
                    }
                    return JSON.stringify(d);
                } catch (e) {
                    return JSON.stringify({err: 'outer: ' + (e && e.message || e)});
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                webView.context?.let { ctx ->
                    com.phoenix.downloader.core.CrashLogger.logNonFatal(
                        ctx,
                        "BrowserButtons",
                        Exception("inject result=$result url=${webView.url}")
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
