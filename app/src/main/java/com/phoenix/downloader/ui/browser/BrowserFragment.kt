package com.phoenix.downloader.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
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
 * Watching happens HERE (user direction 2026-10-22: browser watch):
 * video taps stay in the page, so playback, Up next and comments are
 * YouTube's own and start instantly — the native detail page cannot
 * match that (stream URLs need a PO token only the web player mints).
 * Our layer on top: per-video download buttons in lists, and the
 * "Video detected" pill on a watch page, which opens the download
 * dialog for the current video.
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

            @android.webkit.JavascriptInterface
            fun onActionClick(action: String, videoUrl: String) {
                // The injected watch-page action bar (Save / Popup /
                // Play Audio / Share / Download). Download and Play
                // Audio open the download dialog; Share uses the
                // system share sheet; Save/Popup are placeholders,
                // same as on the native detail page.
                activity?.runOnUiThread {
                    when (action) {
                        "download", "audio" -> onDownloadClick(videoUrl)
                        "share" -> {
                            try {
                                val send = android.content.Intent(
                                    android.content.Intent.ACTION_SEND
                                ).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, videoUrl)
                                }
                                activity?.startActivity(
                                    android.content.Intent.createChooser(send, null)
                                )
                            } catch (e: Exception) { /* no share target */ }
                        }
                        else -> {
                            val label = if (action == "save") "Save" else "Popup"
                            context?.let { ctx ->
                                android.widget.Toast.makeText(
                                    ctx, "$label — coming soon",
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

        // "Video detected — tap to download" pill: the page keeps
        // playing (browser watch); the pill is the download path for
        // the current video and opens the download dialog directly.
        binding.tvDetectHint.setOnClickListener {
            val url = pendingVideoUrl ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                YtDlpManager.fetchVideoInfo(url)
                    .onSuccess { DownloadDialogFragment.newInstance(it.url, it.title).show(parentFragmentManager, "DownloadDialog") }
                    .onFailure { e ->
                        context?.let { ctx ->
                            android.widget.Toast.makeText(
                                ctx,
                                e.message ?: "Could not fetch video",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            // No shouldOverrideUrlLoading interception (removed
            // 2026-10-22 with the browser-watch direction): video taps
            // load YouTube's own watch page in place — instant playback,
            // YouTube's own Up next — instead of bouncing out to our
            // native detail page.
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
                // Inject our action bar on YouTube watch pages
                injectWatchActions(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Catches SPA pushState/replaceState navigations
                binding.tvUrl.text = url
                checkForVideo(url)
                // Re-inject per-video download buttons after SPA navigation
                injectDownloadButtons(view)
                injectWatchActions(view)
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

    private fun checkForVideo(url: String) {
        val site = UrlDetector.detect(url)
        pendingVideoUrl = if (site != null) url else null
        // FAB disabled - user wants per-video buttons only, not FAB
        binding.fabDownload.isVisible = false
        // On YouTube watch pages the injected action bar (big red
        // Download) is the download path, so the pill stays hidden
        // there; on Shorts / Facebook / TikTok the pill remains it.
        val hasActionBar = site == UrlDetector.Site.YOUTUBE && url.contains("/watch")
        binding.tvDetectHint.isVisible = pendingVideoUrl != null && !hasActionBar
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
        // list-item renderer itself; when YouTube's markup has no known
        // renderer ancestor (home-feed drift, field log 2026-10-21), a
        // fallback climbs to the single-video card container. The host
        // must contain a thumbnail image, and the button scrolls away
        // with its item.
        // YouTube recycles rows while scrolling, so the button stores its
        // video id in data-vid and each scan refreshes it.
        val js = """
            (function() {
                try {
                    var debug = {anchors: 0, matched: 0, added: 0, refreshed: 0, noThumb: 0, inPlayer: 0, noHost: 0, fbAdded: 0, vis: null, err: null};
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
                    function fallbackHost(link) {
                        // Home-feed markup drift (field log 2026-10-21):
                        // anchors matched but no known renderer ancestor
                        // existed, so every item was skipped (added=0).
                        // Climb to the card instead: the highest ancestor
                        // that still holds exactly ONE distinct video and
                        // a thumbnail image.
                        var node = link, best = null;
                        for (var depth = 0; depth < 8 && node && node !== document.body; depth++) {
                            node = node.parentElement;
                            if (!node) break;
                            if (!node.querySelector('img')) continue;
                            var anchors = node.querySelectorAll('a[href*="watch"]');
                            var vids = {};
                            var distinct = 0;
                            for (var j = 0; j < anchors.length; j++) {
                                var v = videoIdOf(anchors[j]);
                                if (v && !vids[v]) { vids[v] = 1; distinct++; }
                            }
                            if (distinct === 1) { best = node; continue; }
                            if (distinct > 1) break;
                        }
                        if (best) {
                            var r = best.getBoundingClientRect();
                            if (r.width < 100 || r.height < 60) return null;
                        }
                        return best;
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
                                var viaFallback = false;
                                if (!host) {
                                    host = fallbackHost(link);
                                    viaFallback = !!host;
                                }
                                if (!host) { debug.noHost++; continue; }
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
                                if (viaFallback) { debug.fbAdded++; stats.fbAdded = (stats.fbAdded || 0) + 1; }
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

    /**
     * Inject OUR action bar into YouTube watch pages (user spec
     * 2026-10-22, from their VidMate screenshots): Save / Popup /
     * Play Audio / Share / Download — with Download big and red at
     * the right end, sitting under the video's own metadata like the
     * detail-page action row did. CEIR-extension model: the page is
     * YouTube's; this bar is our overlay inside it. Clicks ride the
     * PhoenixDownloader bridge (onActionClick). The bar re-anchors
     * itself on SPA navigations (it keys on the current video id),
     * and reports which anchor matched to the crash log so the field
     * can verify placement.
     */
    private fun injectWatchActions(webView: WebView) {
        val js = """
            (function() {
                try {
                    var stats = window._phxBar || (window._phxBar = {added: 0, rebuilt: 0});
                    function currentVid() {
                        var m = location.href.match(/[?&]v=([\w-]{11})/);
                        return m ? m[1] : null;
                    }
                    function svgStroke(inner) {
                        return '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#0f0f0f" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
                    }
                    function svgShare() {
                        return '<svg viewBox="0 0 24 24" width="24" height="24" fill="#0f0f0f"><path d="M13 4.5L21 12l-8 7.5v-4.2c-5-.3-7.7 1.4-9.6 4.4.3-6.4 3.4-10.3 9.6-11.4z"/></svg>';
                    }
                    function svgDownload() {
                        return '<svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="#e62117" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v11"/><path d="M6.5 9.5L12 15l5.5-5.5"/><path d="M4 20h16"/></svg>';
                    }
                    function cell(action, icon, label, color) {
                        var d = document.createElement('div');
                        d.setAttribute('data-phx-action', action);
                        d.style.cssText = 'flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;padding:4px 0;cursor:pointer;';
                        d.innerHTML = icon + '<span style="font-size:11px;color:' + color + ';' + (action === 'download' ? 'font-weight:700;' : '') + '">' + label + '</span>';
                        d.addEventListener('click', function(e) {
                            e.stopPropagation();
                            e.preventDefault();
                            var v = currentVid();
                            if (v && window.PhoenixDownloader && PhoenixDownloader.onActionClick) {
                                PhoenixDownloader.onActionClick(action, 'https://www.youtube.com/watch?v=' + v);
                            }
                        }, true);
                        return d;
                    }
                    function ensure() {
                        var vid = currentVid();
                        if (!vid) return 'no-vid';
                        if (location.href.indexOf('/watch') < 0) return 'not-watch';
                        var bar = document.getElementById('phoenix-action-bar');
                        if (bar && bar.getAttribute('data-vid') === vid) return 'exists';
                        if (bar) { bar.remove(); stats.rebuilt++; }
                        var anchorAfter = null, anchorBefore = null, how = '';
                        var info = document.querySelector('ytm-slim-video-information-renderer');
                        if (info) { anchorAfter = info; how = 'info'; }
                        if (!anchorAfter) {
                            var like = document.querySelector('ytm-like-button-renderer, ytm-segmented-like-dislike-button-renderer');
                            if (like && like.parentElement) { anchorAfter = like.parentElement; how = 'like-parent'; }
                        }
                        if (!anchorAfter) {
                            var comments = document.querySelector('ytm-comments-entry-point-header-renderer, ytm-comment-section-renderer');
                            if (comments) { anchorBefore = comments; how = 'comments'; }
                        }
                        if (!anchorAfter && !anchorBefore) return 'no-anchor';
                        bar = document.createElement('div');
                        bar.id = 'phoenix-action-bar';
                        bar.setAttribute('data-vid', vid);
                        bar.style.cssText = 'display:flex;align-items:flex-start;background:#ffffff;border-top:1px solid #e5e5e5;border-bottom:1px solid #e5e5e5;padding:8px 6px;margin:6px 0;position:relative;z-index:50;';
                        bar.appendChild(cell('save', svgStroke('<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M12 8v6"/><path d="M9 11l3 3 3-3"/>'), 'Save', '#0f0f0f'));
                        bar.appendChild(cell('popup', svgStroke('<rect x="8" y="9" width="12" height="11" rx="2"/><path d="M16 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>'), 'Popup', '#0f0f0f'));
                        bar.appendChild(cell('audio', svgStroke('<path d="M4 16v-3a8 8 0 0 1 16 0v3"/><rect x="3" y="15" width="4" height="6" rx="2"/><rect x="17" y="15" width="4" height="6" rx="2"/>'), 'Play Audio', '#0f0f0f'));
                        bar.appendChild(cell('share', svgShare(), 'Share', '#0f0f0f'));
                        bar.appendChild(cell('download', svgDownload(), 'Download', '#e62117'));
                        if (anchorBefore) {
                            anchorBefore.parentNode.insertBefore(bar, anchorBefore);
                        } else {
                            anchorAfter.parentNode.insertBefore(bar, anchorAfter.nextSibling);
                        }
                        stats.added++;
                        try {
                            if (window.PhoenixDownloader && PhoenixDownloader.reportDiag) {
                                PhoenixDownloader.reportDiag(JSON.stringify({bar: 'added', how: how, vid: vid, stats: stats}));
                            }
                        } catch (e) {}
                        return 'added-' + how;
                    }
                    var r = ensure();
                    var tries = 0;
                    var timer = setInterval(function() {
                        tries++;
                        ensure();
                        if (tries >= 12) clearInterval(timer);
                    }, 1000);
                    if (!window._phoenixBarObserver && document.body) {
                        try {
                            window._phoenixBarObserver = new MutationObserver(function() { ensure(); });
                            window._phoenixBarObserver.observe(document.body, { childList: true, subtree: true });
                        } catch (e) {}
                    }
                    return JSON.stringify({bar: r, stats: stats});
                } catch (e) {
                    return JSON.stringify({bar: 'err', err: 'outer: ' + (e && e.message || e)});
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                webView.context?.let { ctx ->
                    com.phoenix.downloader.core.CrashLogger.logNonFatal(
                        ctx,
                        "BrowserActions",
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
