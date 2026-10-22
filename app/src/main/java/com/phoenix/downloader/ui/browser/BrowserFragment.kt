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
            fun onDownloadClick(videoUrl: String, title: String) {
                // The dialog opens IMMEDIATELY with what the page
                // already knows (url + title). Until 2026-10-22 this
                // waited for a full fetchVideoInfo extraction first —
                // that gate was most of the "Fetching" wait the user
                // felt. The dialog now fills any gaps (duration, exact
                // title) in the background while formats load.
                activity?.runOnUiThread {
                    openDownloadDialogNow(videoUrl, title)
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

        // "Video detected — tap to download" pill: shown on
        // Facebook / TikTok / Shorts pages (YouTube watch pages have
        // the injected red DOWNLOAD button instead — no pill there,
        // user 2026-10-22). Opens the dialog immediately.
        binding.tvDetectHint.setOnClickListener {
            val url = pendingVideoUrl ?: return@setOnClickListener
            openDownloadDialogNow(url, binding.webview.title ?: "")
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
            openDownloadDialogNow(url, binding.webview.title ?: "")
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
    /**
     * Open the download dialog NOW with whatever is already known
     * (url, a best-effort title, a derived thumbnail). No extraction
     * gates the dialog — the dialog itself loads formats (fast-first
     * chain) and back-fills title/duration in the background.
     */
    private fun openDownloadDialogNow(url: String, title: String) {
        val id = com.phoenix.downloader.core.YouTubeApiClient.extractVideoId(url)
        val thumb = if (id != null) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else ""
        val cleanTitle = title.removeSuffix(" - YouTube").trim()
        DownloadDialogFragment.newInstance(url, cleanTitle, thumb, 0)
            .show(parentFragmentManager, "DownloadDialog")
    }

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
                    var debug = {anchors: 0, matched: 0, added: 0, refreshed: 0, noThumb: 0, inPlayer: 0, noHost: 0, fbAdded: 0, hdr: 0, vis: null, err: null};
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
                                btn.style.cssText = 'position:absolute;right:30px;bottom:4px;width:44px;height:44px;' +
                                    'color:#e62117;display:flex;align-items:center;justify-content:center;' +
                                    'font-size:28px;font-weight:700;z-index:9999;cursor:pointer;background:transparent;';
                                btn.addEventListener('click', function(e) {
                                    e.stopPropagation();
                                    e.preventDefault();
                                    var t = e.currentTarget;
                                    var v = t ? t.getAttribute('data-vid') : null;
                                    var ttl = '';
                                    try {
                                        var h = t.parentElement && t.parentElement.querySelector('h3');
                                        if (h) ttl = (h.textContent || '').trim();
                                    } catch (e4) {}
                                    if (v) PhoenixDownloader.onDownloadClick('https://www.youtube.com/watch?v=' + v, ttl);
                                }, true);
                                host.appendChild(btn);
                                debug.added++; stats.added++;
                                if (viaFallback) { debug.fbAdded++; stats.fbAdded = (stats.fbAdded || 0) + 1; }
                                if (!debug.vis) debug.vis = probeVis(btn);
                            }
                            // Mix / playlist panel header: one red ⬇ at
                            // the header's right (left of YouTube's own
                            // share icon) for the CURRENT video — the
                            // user's VidMate Mix screenshot shows it
                            // there (2026-10-22).
                            try {
                                var panel = document.querySelector('ytm-playlist-panel-renderer');
                                if (panel && !panel.querySelector('[data-phoenix-hdr]')) {
                                    var hdr = panel.firstElementChild;
                                    if (hdr) {
                                        try {
                                            if (window.getComputedStyle(hdr).position === 'static') {
                                                hdr.style.position = 'relative';
                                            }
                                        } catch (e5) {}
                                        var hb = document.createElement('div');
                                        hb.setAttribute('data-phoenix-hdr', '1');
                                        hb.textContent = '⬇';
                                        hb.style.cssText = 'position:absolute;right:52px;top:50%;transform:translateY(-50%);' +
                                            'width:44px;height:44px;color:#e62117;display:flex;align-items:center;justify-content:center;' +
                                            'font-size:28px;font-weight:700;z-index:9999;cursor:pointer;background:transparent;';
                                        hb.addEventListener('click', function(e) {
                                            e.stopPropagation();
                                            e.preventDefault();
                                            var m = location.href.match(/[?&]v=([\w-]{11})/);
                                            if (m) {
                                                PhoenixDownloader.onDownloadClick(
                                                    'https://www.youtube.com/watch?v=' + m[1],
                                                    document.title.replace(/\s*-\s*YouTube\s*$/, ''));
                                            }
                                        }, true);
                                        hdr.appendChild(hb);
                                        debug.hdr++;
                                    }
                                }
                            } catch (e6) {}
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
     * Inject OUR download button into YouTube watch pages (user
     * spec 2026-10-22, final mockup): ONE red pill — a dark-red
     * circle with a white arrow at the FRONT + "DOWNLOAD" in white —
     * centered in a plain row under the video's metadata, above the
     * comments. No other cells, and no native pill on watch pages.
     * Build 293's bar never appeared in the field because its three
     * anchors matched nothing on the user's page, so this version
     * climbs a ladder of anchors and reports a DOM census (which
     * candidate elements exist) to the crash log: a placement miss
     * on any layout is diagnosable from ONE log, no guessing.
     */
    private fun injectWatchActions(webView: WebView) {
        val js = """
            (function() {
                try {
                    var stats = window._phxPill || (window._phxPill = {added: 0, rebuilt: 0, censusSent: false});
                    var CANDS = ['ytm-slim-video-information-renderer','ytm-video-information-renderer',
                        'ytm-like-button-renderer','ytm-segmented-like-dislike-button-renderer',
                        'ytm-comments-entry-point-header-renderer','ytm-comment-section-renderer',
                        'ytm-expandable-video-description-renderer','ytm-item-section-renderer',
                        'ytm-compact-video-renderer','ytm-playlist-panel-renderer','#player-container-id','ytm-player'];
                    function currentVid() {
                        var m = location.href.match(/[?&]v=([\w-]{11})/);
                        return m ? m[1] : null;
                    }
                    function census() {
                        var c = {};
                        for (var i = 0; i < CANDS.length; i++) {
                            try { c[CANDS[i]] = !!document.querySelector(CANDS[i]); } catch (e) { c[CANDS[i]] = false; }
                        }
                        return c;
                    }
                    function report(obj) {
                        try {
                            if (window.PhoenixDownloader && PhoenixDownloader.reportDiag) {
                                PhoenixDownloader.reportDiag(JSON.stringify(obj));
                            }
                        } catch (e) {}
                    }
                    function buildPill(vid) {
                        var row = document.createElement('div');
                        row.id = 'phoenix-dl-row';
                        row.setAttribute('data-vid', vid);
                        row.style.cssText = 'display:flex;justify-content:center;background:#ffffff;' +
                            'border-top:1px solid #eeeeee;border-bottom:1px solid #eeeeee;' +
                            'padding:11px 0;margin:6px 0;position:relative;z-index:60;';
                        var pill = document.createElement('span');
                        pill.style.cssText = 'display:inline-flex;align-items:center;gap:9px;border-radius:999px;' +
                            'background:linear-gradient(180deg,#f23a2a,#d80f18 55%,#c40d16);' +
                            'padding:5px 19px 5px 5px;cursor:pointer;' +
                            'box-shadow:0 3px 8px rgba(216,15,24,.35), inset 0 1px 0 rgba(255,255,255,.35);';
                        var circ = document.createElement('span');
                        circ.style.cssText = 'width:28px;height:28px;border-radius:50%;flex:none;' +
                            'background:radial-gradient(circle at 35% 30%, #c0272d, #7d0a10);' +
                            'display:flex;align-items:center;justify-content:center;' +
                            'box-shadow:inset 0 2px 4px rgba(0,0,0,.45);';
                        circ.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16"><path fill="#ffffff" d="M12 3.5v10.1l3.4-3.4 1.9 1.9L12 17.4l-5.3-5.3 1.9-1.9 3.4 3.4V3.5zM5 19.2h14V21H5z"/></svg>';
                        var txt = document.createElement('span');
                        txt.textContent = 'DOWNLOAD';
                        txt.style.cssText = 'color:#ffffff;font-weight:800;font-size:14px;letter-spacing:.6px;';
                        pill.appendChild(circ);
                        pill.appendChild(txt);
                        pill.addEventListener('click', function(e) {
                            e.stopPropagation();
                            e.preventDefault();
                            var v = currentVid();
                            if (v && window.PhoenixDownloader && PhoenixDownloader.onDownloadClick) {
                                PhoenixDownloader.onDownloadClick(
                                    'https://www.youtube.com/watch?v=' + v,
                                    document.title.replace(/\s*-\s*YouTube\s*$/, ''));
                            }
                        }, true);
                        row.appendChild(pill);
                        return row;
                    }
                    function ensure() {
                        var vid = currentVid();
                        if (!vid) return 'no-vid';
                        if (location.href.indexOf('/watch') < 0) return 'not-watch';
                        var row = document.getElementById('phoenix-dl-row');
                        if (row && row.getAttribute('data-vid') === vid) return 'exists';
                        if (row) { row.remove(); stats.rebuilt++; }
                        var after = null, before = null, how = '';
                        var info = document.querySelector('ytm-slim-video-information-renderer');
                        if (info) { after = info; how = 'slim-info'; }
                        if (!after) {
                            var info2 = document.querySelector('ytm-video-information-renderer');
                            if (info2) { after = info2; how = 'info'; }
                        }
                        if (!after) {
                            var like = document.querySelector('ytm-like-button-renderer, ytm-segmented-like-dislike-button-renderer');
                            if (like) {
                                after = like.closest('ytm-slim-video-information-renderer') ||
                                    (like.parentElement && like.parentElement.parentElement) ||
                                    like.parentElement;
                                how = 'like';
                            }
                        }
                        if (!after && !before) {
                            var desc = document.querySelector('ytm-expandable-video-description-renderer');
                            if (desc) {
                                after = desc.closest('ytm-item-section-renderer') || desc;
                                how = 'description';
                            }
                        }
                        if (!after && !before) {
                            var comments = document.querySelector('ytm-comments-entry-point-header-renderer, ytm-comment-section-renderer');
                            if (comments) {
                                before = comments.closest('ytm-item-section-renderer') || comments;
                                how = 'comments';
                            }
                        }
                        if (!after && !before) {
                            var section = document.querySelector('ytm-item-section-renderer');
                            if (section) { before = section; how = 'first-section'; }
                        }
                        if (!after && !before) {
                            if (!stats.censusSent) {
                                stats.censusSent = true;
                                report({pill: 'no-anchor', vid: vid, census: census()});
                            }
                            return 'no-anchor';
                        }
                        row = buildPill(vid);
                        if (before) {
                            before.parentNode.insertBefore(row, before);
                        } else {
                            after.parentNode.insertBefore(row, after.nextSibling);
                        }
                        stats.added++;
                        report({pill: 'added', how: how, vid: vid, stats: stats});
                        return 'added-' + how;
                    }
                    var r = ensure();
                    var tries = 0;
                    var timer = setInterval(function() {
                        tries++;
                        ensure();
                        if (tries >= 12) clearInterval(timer);
                    }, 1000);
                    if (!window._phoenixPillObserver && document.body) {
                        try {
                            window._phoenixPillObserver = new MutationObserver(function() { ensure(); });
                            window._phoenixPillObserver.observe(document.body, { childList: true, subtree: true });
                        } catch (e) {}
                    }
                    return JSON.stringify({pill: r, stats: stats});
                } catch (e) {
                    return JSON.stringify({pill: 'err', err: 'outer: ' + (e && e.message || e)});
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                webView.context?.let { ctx ->
                    com.phoenix.downloader.core.CrashLogger.logNonFatal(
                        ctx,
                        "BrowserPill",
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
