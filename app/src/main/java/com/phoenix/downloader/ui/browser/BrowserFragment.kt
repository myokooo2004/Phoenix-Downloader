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

    /** Host hooks (set by HomeBrowserFragment in home mode): page URL
     *  changes and WebView scroll position, for the top-bar
     *  auto-hide behavior. Null in the standalone browser. */
    var onHostUrlChanged: ((String) -> Unit)? = null
    var onHostScroll: ((Int) -> Unit)? = null

    /** The WebView's current URL, if the view exists. */
    fun currentUrl(): String? = if (_binding != null) binding.webview.url else null

    /**
     * Standalone (pushed) browser only: on a YouTube watch page the
     * toolbar and bottom bar hide too, so the video is full at the
     * top (user spec 2026-10-22 — the same rule the home browser
     * follows; in home mode both bars are always hidden already).
     * The phone back key still leaves the page.
     */
    private fun updateStandaloneChrome(url: String) {
        if (arguments?.getBoolean(ARG_HOME) == true) return
        if (_binding == null) return
        val watch = url.contains("youtube.com/watch") || url.contains("youtu.be/") ||
            url.contains("youtube.com/shorts")
        val vis = if (watch) View.GONE else View.VISIBLE
        binding.toolbarBrowser.visibility = vis
        binding.bottomBar.visibility = vis
    }
    private var pendingVideoUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Home mode (2026-10-22 browser-base spec): the browser IS the
        // home screen, hosted under OUR top bar — its own toolbar and
        // bottom bar stay hidden; the page is YouTube itself.
        if (arguments?.getBoolean(ARG_HOME) == true) {
            binding.toolbarBrowser.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
        }
        // Host scroll feed (top-bar auto-hide in the home host).
        binding.webview.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            onHostScroll?.invoke(scrollY)
        }
        val wv = binding.webview
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        // Hide WebView indicator so sites like TikTok don't block/redirect
        wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
        // Default TLS handling (no onReceivedSslError override) -> no security errors.

        // JavaScript bridge for per-video download buttons (user-style)
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
                onHostUrlChanged?.invoke(url)
                updateStandaloneChrome(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // SPA sites (YouTube/Facebook) update URL via JS; re-check on finish
                val currentUrl = view.url ?: url
                binding.tvUrl.text = currentUrl
                checkForVideo(currentUrl)
                updateStandaloneChrome(currentUrl)
                // Save cookies for yt-dlp bot bypass (from Python file insight)
                try {
                    com.phoenix.downloader.core.CookieHelper.saveCookiesForUrl(
                        requireContext(), currentUrl
                    )
                } catch (e: Exception) { /* ignore */ }
                // Inject user-style per-video download buttons
                injectDownloadButtons(view)
                // Inject our action bar on YouTube watch pages
                injectWatchActions(view)
                // Page chrome: hide YouTube's own header everywhere
                injectPageStyle(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Catches SPA pushState/replaceState navigations
                binding.tvUrl.text = url
                checkForVideo(url)
                onHostUrlChanged?.invoke(url)
                updateStandaloneChrome(url)
                // Re-inject per-video download buttons after SPA navigation
                injectDownloadButtons(view)
                injectWatchActions(view)
                injectPageStyle(view)
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
                    // Trusted Types (field log 2026-10-22 15:08): YouTube's
                    // page policy rejects HTML-string assignment
                    // ('This document requires TrustedHTML assignment'),
                    // which killed EVERY button creation. Build the icon
                    // with DOM APIs instead - no HTML parsing involved.
                    function dlIconSvg(size) {
                        var NS = 'http://www.w3.org/2000/svg';
                        var svg = document.createElementNS(NS, 'svg');
                        svg.setAttribute('viewBox', '0 0 24 24');
                        svg.setAttribute('width', size);
                        svg.setAttribute('height', size);
                        svg.setAttribute('fill', 'none');
                        svg.setAttribute('stroke', '#e62117');
                        svg.setAttribute('stroke-width', '2.1');
                        svg.setAttribute('stroke-linecap', 'round');
                        svg.setAttribute('stroke-linejoin', 'round');
                        var ds = ['M12 3.8v10.7', 'M6.6 9.9 12 15.3l5.4-5.4',
                            'M4 15v3.6A2.4 2.4 0 0 0 6.4 21h11.2A2.4 2.4 0 0 0 20 18.6V15'];
                        for (var i = 0; i < ds.length; i++) {
                            var pth = document.createElementNS(NS, 'path');
                            pth.setAttribute('d', ds[i]);
                            svg.appendChild(pth);
                        }
                        return svg;
                    }
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
                    function styleItem(host) {
                        // Rounded thumbnail + YouTube's own view count
                        // ON the thumbnail (user mockups v5/v6
                        // 2026-10-22). Geometry is re-evaluated on
                        // EVERY call: the first pass can run while the
                        // card is still a narrow skeleton, and locking
                        // radius/margins then left the first cards
                        // full-bleed with square corners while later
                        // cards were inset (field screenshots
                        // 2026-10-22 15:36). The player surface is
                        // never styled: an autoplay next-preview that
                        // overlaps it got a views stamp in the field.
                        try {
                            var img = host.querySelector('img');
                            if (!img) return;
                            var box = img.closest('a') || img.parentElement;
                            if (!box) return;
                            if (box.querySelector('video')) return;
                            var big = false;
                            try { big = box.getBoundingClientRect().width > 250; } catch (e) {}
                            try {
                                var plr = document.querySelector('#player-container-id, ytm-player');
                                if (plr) {
                                    var br = box.getBoundingClientRect();
                                    var pr = plr.getBoundingClientRect();
                                    if (br.height > 0 && pr.height > 0 &&
                                        br.top < pr.bottom && br.bottom > pr.top &&
                                        br.left < pr.right && br.right > pr.left) return;
                                }
                            } catch (e) {}
                            try {
                                if (window.getComputedStyle(box).position === 'static') {
                                    box.style.position = 'relative';
                                }
                            } catch (e) {}
                            box.style.borderRadius = big ? '18px' : '10px';
                            box.style.overflow = 'hidden';
                            try {
                                box.classList.add(big ? 'phx-tb' : 'phx-tb-sm');
                                box.classList.remove(big ? 'phx-tb-sm' : 'phx-tb');
                            } catch (e) {}
                            box.style.marginLeft = big ? '10px' : '';
                            box.style.marginRight = big ? '10px' : '';
                            if (!box.hasAttribute('data-phoenix-round')) {
                                box.setAttribute('data-phoenix-round', '1');
                                stats.rounded = (stats.rounded || 0) + 1;
                                debug.rounded = (debug.rounded || 0) + 1;
                            }
                            var badge = box.querySelector('[data-phoenix-views]');
                            if (big) {
                                var m = (host.textContent || '').match(/([\d.,]+\s*[KMB]?\s*views?)/i);
                                if (m) {
                                    var txt = m[1].replace(/\s+/g, ' ').trim();
                                    if (!badge) {
                                        badge = document.createElement('span');
                                        badge.setAttribute('data-phoenix-views', '1');
                                        badge.style.cssText = 'position:absolute;left:10px;bottom:8px;color:#fff;' +
                                            'font-size:12.5px;font-weight:500;text-shadow:0 1px 4px rgba(0,0,0,.7);' +
                                            'z-index:60;pointer-events:none;';
                                        box.appendChild(badge);
                                        stats.views = (stats.views || 0) + 1;
                                        debug.views = (debug.views || 0) + 1;
                                    }
                                    if (badge.textContent !== txt) badge.textContent = txt;
                                } else if (badge) {
                                    badge.remove();
                                }
                            } else if (badge) {
                                badge.remove();
                            }
                        } catch (e) {}
                    }
                    function padMeta(host) {
                        // Keep the meta text out of the button's
                        // corner: on long bylines the channel/views/
                        // time text ran UNDER the icon (field
                        // screenshot 2026-10-22 16:11). Give the
                        // item's text block right padding once; the
                        // text then truncates before the button zone.
                        try {
                            if (host.getAttribute('data-phoenix-pad') === '1') return;
                            var h3 = host.querySelector('h3');
                            if (!h3) return;
                            var img = host.querySelector('img');
                            var node = h3, padEl = null;
                            for (var d = 0; d < 4; d++) {
                                node = node.parentElement;
                                if (!node || node === host) break;
                                if (/views/i.test(node.textContent || '')) { padEl = node; break; }
                                if (img && node.contains(img)) break;
                            }
                            var el = padEl || h3.parentElement;
                            if (el && el !== host) {
                                el.style.paddingRight = '54px';
                                host.setAttribute('data-phoenix-pad', '1');
                                stats.padded = (stats.padded || 0) + 1;
                            }
                        } catch (e) {}
                    }
                    function addButtons(fullPass) {
                        stats.passes++;
                        debug.added = 0; debug.refreshed = 0; debug.rounded = 0; debug.views = 0; debug.noCard = 0;
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
                                // Card-level host: a thumb-only host
                                // parks the button ON the thumbnail
                                // (field screenshots 2026-10-22 15:36).
                                // Climb while the parent still holds
                                // exactly this one video and adds the
                                // title the host lacks.
                                try {
                                    var cg = 0;
                                    while (cg++ < 3 && !host.querySelector('h3') && host.parentElement) {
                                        var par = host.parentElement;
                                        var pas = par.querySelectorAll('a[href*="watch"]');
                                        var pseen = {}, pd = 0;
                                        for (var pi = 0; pi < pas.length; pi++) {
                                            var pm = (pas[pi].getAttribute('href') || '').match(/[?&]v=([\w-]{11})/);
                                            if (pm && !pseen[pm[1]]) { pseen[pm[1]] = 1; pd++; }
                                        }
                                        if (pd === 1 && par.querySelector('h3')) { host = par; } else { break; }
                                    }
                                } catch (e) {}
                                // Card-only rule (user verdict
                                // 2026-10-22 15:57: buttons sat ON the
                                // up-next thumbnails): a fallback host
                                // that still has no title is not a card -
                                // skip this pass instead of parking the
                                // button on the thumbnail. The retries
                                // catch the item once its card exists.
                                if (viaFallback && !host.querySelector('h3')) {
                                    debug.noCard = (debug.noCard || 0) + 1;
                                    continue;
                                }
                                if (!host.querySelector('img')) { debug.noThumb++; continue; }
                                if (host.closest('ytm-player,#player,.html5-video-player,ytm-inline-player')) {
                                    debug.inPlayer++; continue;
                                }
                                var existing = host.querySelector('[data-phoenix-btn]');
                                if (existing) {
                                    // Recycled row: refresh the target
                                    // video id cheaply on every pass;
                                    // the expensive geometry re-check
                                    // (styleItem/padMeta - forced layouts)
                                    // runs only on the 1s burst passes.
                                    // Running it on EVERY mutation pass
                                    // saturated the page's main thread
                                    // and starved YouTube's own rendering
                                    // (field 2026-10-22: blank pages).
                                    if (fullPass) { styleItem(host); padMeta(host); }
                                    if (existing.getAttribute('data-vid') !== vid) {
                                        existing.setAttribute('data-vid', vid);
                                    }
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
                                btn.appendChild(dlIconSvg(27));
                                btn.style.cssText = 'position:absolute;right:30px;bottom:4px;width:44px;height:44px;' +
                                    'display:flex;align-items:center;justify-content:center;' +
                                    'z-index:9999;cursor:pointer;background:transparent;';
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
                                styleItem(host);
                                padMeta(host);
                                debug.added++; stats.added++;
                                if (viaFallback) { debug.fbAdded++; stats.fbAdded = (stats.fbAdded || 0) + 1; }
                                if (!debug.vis) debug.vis = probeVis(btn);
                            }
                            // Mix / playlist panel header: one red ⬇ at
                            // the header's right (left of YouTube's own
                            // share icon) for the CURRENT video — the
                            // user's Mix reference screenshot shows it
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
                                        hb.appendChild(dlIconSvg(27));
                                        hb.style.cssText = 'position:absolute;right:52px;top:50%;transform:translateY(-50%);' +
                                            'width:44px;height:44px;display:flex;align-items:center;justify-content:center;' +
                                            'z-index:9999;cursor:pointer;background:transparent;';
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
                    var d = addButtons(true);
                    // One burst per page at a time: every SPA
                    // navigation re-injects, and stacked 1s bursts
                    // from earlier navigations kept the main thread
                    // busy long after their page was gone.
                    if (window._phxBtnTimer) clearInterval(window._phxBtnTimer);
                    var tries = 0;
                    var timer = setInterval(function() {
                        tries++;
                        addButtons(true);
                        if (tries >= 10) { clearInterval(timer); window._phxBtnTimer = null; reportOnce(); }
                    }, 1000);
                    window._phxBtnTimer = timer;
                    setTimeout(reportOnce, 25000);
                    if (!window._phoenixObserver && document.body) {
                        try {
                            window._phoenixObserver = new MutationObserver(function() {
                                // Debounced: YouTube mutates its DOM
                                // constantly, and a full scan per
                                // mutation starved the page itself.
                                if (window._phxBtnPend) return;
                                window._phxBtnPend = setTimeout(function() {
                                    window._phxBtnPend = null;
                                    addButtons(false);
                                }, 650);
                            });
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
     * Page chrome control (user spec 2026-10-22, extended same day):
     * hide YouTube's header AND bottom pivot bar AND the filter
     * chips bar; hide Shorts shelves/items (the user does not want
     * Shorts in the feeds); round item thumbnails at the CSS layer
     * too (the per-item inline styling alone flickered round/square
     * as YouTube rebuilt cards). Everything is census-reported per
     * page so a miss is diagnosable from the log, never guessed.
     * The player surface itself is never rounded or hidden - the
     * playing video stays full-bleed and square, as specified.
     */
    private fun injectPageStyle(webView: WebView) {
        val js = """
            (function() {
                try {
                    var HEAD = ['ytm-header', 'header', '#header', 'ytm-mobile-topbar-renderer', '#mobile-topbar'];
                    var PIVOT = ['ytm-pivot-bar-renderer', '#pivot-bar', 'ytm-pivot-bar-item-renderer'];
                    var CHIPS = ['ytm-feed-filter-chip-bar-renderer', 'ytm-chip-bar-renderer', '#chips-wrapper', 'ytm-search-filter-chip-bar-renderer'];
                    var ALL = HEAD.concat(PIVOT).concat(CHIPS);
                    var THUMB_SELS = ['ytm-media-item .media-item-thumbnail-container',
                        'ytm-video-with-context-renderer .video-thumbnail-container',
                        'ytm-compact-video-renderer .compact-video-thumbnail-container',
                        'ytm-playlist-panel-video-renderer .media-item-thumbnail-container',
                        'ytm-rich-item-renderer .video-thumbnail-container'];
                    function firstMatch(list) {
                        for (var i = 0; i < list.length; i++) {
                            if (document.querySelector(list[i])) return list[i];
                        }
                        return null;
                    }
                    function hideEl(el) {
                        try { el.style.setProperty('display', 'none', 'important'); } catch (e) {}
                    }
                    function isShortsOnly(el) {
                        // A container may be hidden for Shorts ONLY if
                        // it holds Shorts links and no normal videos.
                        // The first version hid any item-section a
                        // Shorts link appeared in - on the results
                        // page that section is the WHOLE results
                        // list, so one Shorts result blanked the page
                        // (field report 2026-10-22 18:17).
                        try {
                            return !!el.querySelector('a[href*="/shorts/"]') &&
                                !el.querySelector('a[href*="/watch"]');
                        } catch (e) { return false; }
                    }
                    function hideShorts() {
                        var n = 0;
                        try {
                            var links = document.querySelectorAll('a[href*="/shorts/"]');
                            for (var i = 0; i < links.length; i++) {
                                var a = links[i];
                                var target = a.closest('ytm-reel-shelf-renderer') ||
                                    a.closest('ytm-media-item, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-rich-item-renderer, ytm-shorts-lockup-view-model');
                                if (!target) {
                                    var sec = a.closest('ytm-rich-shelf-renderer, ytm-item-section-renderer, ytm-shelf-renderer');
                                    if (sec && isShortsOnly(sec)) target = sec;
                                }
                                if (target && target.style.display !== 'none' && isShortsOnly(target)) { hideEl(target); n++; }
                            }
                        } catch (e) {}
                        return n;
                    }
                    function applyHide() {
                        var hidden = 0;
                        for (var i = 0; i < ALL.length; i++) {
                            var els = document.querySelectorAll(ALL[i]);
                            for (var j = 0; j < els.length; j++) { hideEl(els[j]); hidden++; }
                        }
                        var sh = hideShorts();
                        if (sh > 0) {
                            window._phxShortsHidden = (window._phxShortsHidden || 0) + sh;
                            if (window._phxShortsHidden !== (window._phxShortsReported || 0)) {
                                window._phxShortsReported = window._phxShortsHidden;
                                try {
                                    if (window.PhoenixDownloader && PhoenixDownloader.reportDiag) {
                                        PhoenixDownloader.reportDiag(JSON.stringify({
                                            shortsHidden: window._phxShortsHidden, href: location.href
                                        }));
                                    }
                                } catch (e) {}
                            }
                        }
                        return hidden;
                    }
                    var st = document.getElementById('phoenix-page-style');
                    if (!st) {
                        st = document.createElement('style');
                        st.id = 'phoenix-page-style';
                        st.textContent = ALL.join(',') + ' { display: none !important; }' +
                            THUMB_SELS.join(',') + ' { border-radius: 14px !important; overflow: hidden !important; }' +
                            '.phx-tb { border-radius: 18px !important; overflow: hidden !important; }' +
                            '.phx-tb-sm { border-radius: 10px !important; overflow: hidden !important; }';
                        (document.head || document.documentElement).appendChild(st);
                    }
                    var hiddenNow = applyHide();
                    if (window._phxChromeTimer) clearInterval(window._phxChromeTimer);
                    var tries = 0;
                    var timer = setInterval(function() {
                        tries++;
                        applyHide();
                        if (tries >= 12) { clearInterval(timer); window._phxChromeTimer = null; }
                    }, 1000);
                    window._phxChromeTimer = timer;
                    if (!window._phoenixChromeObserver && document.body) {
                        try {
                            window._phoenixChromeObserver = new MutationObserver(function() {
                                if (window._phxChromePend) return;
                                window._phxChromePend = setTimeout(function() {
                                    window._phxChromePend = null;
                                    applyHide();
                                }, 800);
                            });
                            window._phoenixChromeObserver.observe(document.body, { childList: true, subtree: true });
                        } catch (e) {}
                    }
                    // Late census (added 2026-10-22): the first
                    // census fires on an empty DOM, so it could
                    // never prove the chips bar, the Shorts shelves
                    // or whether items rendered at all. Re-report
                    // once after 5s with the loaded page's numbers.
                    if (!window._phxLateCensus) {
                        window._phxLateCensus = 1;
                        setTimeout(function() {
                            try {
                                var lc = {};
                                for (var i2 = 0; i2 < ALL.length; i2++) lc[ALL[i2]] = !!document.querySelector(ALL[i2]);
                                if (window.PhoenixDownloader && PhoenixDownloader.reportDiag) {
                                    PhoenixDownloader.reportDiag(JSON.stringify({
                                        lateCensus: true,
                                        census: lc,
                                        anchorsNow: document.querySelectorAll('a[href*="watch"]').length,
                                        shortsLinks: document.querySelectorAll('a[href*="/shorts/"]').length,
                                        shortsHidden: window._phxShortsHidden || 0,
                                        href: location.href
                                    }));
                                }
                            } catch (e) {}
                        }, 5000);
                    }
                    if (!window._phoenixChromeSent) window._phoenixChromeSent = {};
                    var key = 'chrome:' + location.href.split('&')[0];
                    if (!window._phoenixChromeSent[key]) {
                        window._phoenixChromeSent[key] = 1;
                        var census = {};
                        for (var c = 0; c < ALL.length; c++) census[ALL[c]] = !!document.querySelector(ALL[c]);
                        var thumbCensus = {};
                        for (var t = 0; t < THUMB_SELS.length; t++) {
                            thumbCensus[THUMB_SELS[t]] = document.querySelectorAll(THUMB_SELS[t]).length;
                        }
                        try {
                            if (window.PhoenixDownloader && PhoenixDownloader.reportDiag) {
                                PhoenixDownloader.reportDiag(JSON.stringify({
                                    chrome: {header: firstMatch(HEAD) || 'none', pivot: firstMatch(PIVOT) || 'none', chips: firstMatch(CHIPS) || 'none', hidden: hiddenNow},
                                    census: census,
                                    thumbSel: thumbCensus,
                                    href: location.href
                                }));
                            }
                        } catch (e) {}
                    }
                    return JSON.stringify({header: firstMatch(HEAD) || 'none', pivot: firstMatch(PIVOT) || 'none', chips: firstMatch(CHIPS) || 'none', hidden: hiddenNow});
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
                        "BrowserChrome",
                        Exception("style result=$result url=${webView.url}")
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
     * comments.
     *
     * Placement, second attempt (field log 2026-10-22 12:33): the
     * 2026 watch layout uses NONE of the classic renderer tags (the
     * census came back all-false), so tag-name anchors alone cannot
     * work. The ladder now ends in STRUCTURAL anchors that exist on
     * every watch page: the title heading's block, the Subscribe
     * button's block, and the first up-next card (found the same
     * way the proven ⬇ injector finds its hosts). The census is
     * reported LATE (after the page has actually rendered) and
     * includes the structural probes.
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
                        'ytm-compact-video-renderer','ytm-playlist-panel-renderer','#player-container-id',
                        'ytm-player','h1','ytm-button-renderer','ytm-watch'];
                    var RENDERERS = 'ytm-video-with-context-renderer,ytm-media-item,ytm-compact-video-renderer,ytm-playlist-video-renderer,ytm-playlist-panel-video-renderer,ytd-rich-item-renderer,ytd-video-renderer,ytd-compact-video-renderer';
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
                        var svgNS = 'http://www.w3.org/2000/svg';
                        var svgEl = document.createElementNS(svgNS, 'svg');
                        svgEl.setAttribute('viewBox', '0 0 24 24');
                        svgEl.setAttribute('width', '16');
                        svgEl.setAttribute('height', '16');
                        var pathEl = document.createElementNS(svgNS, 'path');
                        pathEl.setAttribute('fill', '#ffffff');
                        pathEl.setAttribute('d', 'M12 3.5v10.1l3.4-3.4 1.9 1.9L12 17.4l-5.3-5.3 1.9-1.9 3.4 3.4V3.5zM5 19.2h14V21H5z');
                        svgEl.appendChild(pathEl);
                        circ.appendChild(svgEl);
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
                    // Structural: climb from a known-present element to
                    // the block that wraps the whole metadata zone.
                    function climbBlock(el, maxDepth) {
                        var node = el;
                        for (var d = 0; d < maxDepth && node && node.parentElement; d++) {
                            node = node.parentElement;
                            if (!node || node === document.body) break;
                            // A block containing the player is the whole
                            // watch column, not metadata - the pill landed
                            // above the player in the field (2026-10-22).
                            var r = node.getBoundingClientRect();
                            if (r.width > window.innerWidth * 0.85 && node.querySelectorAll('button, [role="button"]').length >= 2 &&
                                !node.querySelector('ytm-player, #player-container-id, video')) {
                                return node;
                            }
                        }
                        return el.parentElement || el;
                    }
                    function firstUpNextHost(vid) {
                        var links = document.querySelectorAll('a[href*="watch"]');
                        for (var i = 0; i < links.length; i++) {
                            var href = links[i].getAttribute('href') || '';
                            var m = href.match(/[?&]v=([\w-]{11})/);
                            if (!m || m[1] === vid) continue;
                            var host = links[i].closest(RENDERERS);
                            if (host) return host.closest('ytm-item-section-renderer') || host;
                            // climb to the card: ancestors with exactly one video
                            var node = links[i], best = null;
                            for (var d = 0; d < 8 && node && node !== document.body; d++) {
                                node = node.parentElement;
                                if (!node) break;
                                if (!node.querySelector('img')) continue;
                                var as = node.querySelectorAll('a[href*="watch"]');
                                var seen = {}, distinct = 0;
                                for (var j = 0; j < as.length; j++) {
                                    var mm = (as[j].getAttribute('href') || '').match(/[?&]v=([\w-]{11})/);
                                    if (mm && !seen[mm[1]]) { seen[mm[1]] = 1; distinct++; }
                                }
                                if (distinct === 1) { best = node; continue; }
                                if (distinct > 1) break;
                            }
                            if (best) return best;
                        }
                        return null;
                    }
                    function ensure() {
                        try {
                            var vid = currentVid();
                            if (!vid) return 'no-vid';
                            if (location.href.indexOf('/watch') < 0) return 'not-watch';
                            // Unmute (user law 2026-10-22: NEVER
                            // muted on watch pages - sound from the
                            // first moment). A page opened from our
                            // native results has no in-page tap, so
                            // YouTube starts muted. A timed window
                            // raced the player; instead install
                            // persistent listeners ONCE: any mute the
                            // player applies is undone on the spot,
                            // for the whole life of the page.
                            try {
                                if (!window._phxUnmuteHook) {
                                    window._phxUnmuteHook = 1;
                                    var unmuteFn = function(ev) {
                                        var v = ev && ev.target;
                                        if (v && v.tagName === 'VIDEO' && v.muted) {
                                            v.muted = false;
                                            try { v.volume = 1; } catch (e) {}
                                            stats.unmuteTries = (stats.unmuteTries || 0) + 1;
                                        }
                                    };
                                    document.addEventListener('volumechange', unmuteFn, true);
                                    document.addEventListener('playing', unmuteFn, true);
                                }
                                var vel0 = document.querySelector('video');
                                if (vel0 && vel0.muted) {
                                    vel0.muted = false;
                                    try { vel0.volume = 1; } catch (e) {}
                                    stats.unmuteTries = (stats.unmuteTries || 0) + 1;
                                }
                                if (vel0 && !vel0.muted && !vel0.paused && vel0.readyState > 0 && !stats.unmuted) {
                                    stats.unmuted = 1;
                                }
                            } catch (e) {}
                            var row = document.getElementById('phoenix-dl-row');
                            if (row && row.getAttribute('data-vid') === vid) {
                                // Fit guard: the pill belongs UNDER the
                                // metadata - if it sits above the
                                // player's bottom edge it is misplaced;
                                // drop it and let the ladder re-anchor.
                                var plEl = document.querySelector('#player-container-id, ytm-player');
                                var misplaced = false;
                                if (plEl) {
                                    try {
                                        var rr = row.getBoundingClientRect();
                                        var prr = plEl.getBoundingClientRect();
                                        if (rr.height > 0 && prr.height > 0 && rr.top < prr.bottom - 4) misplaced = true;
                                    } catch (e) {}
                                }
                                if (!misplaced) return 'exists';
                                row.remove(); stats.rebuilt++;
                                report({pill: 'misplaced', vid: vid, stats: stats});
                                row = null;
                            }
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
                                    after = like.closest('ytm-slim-video-information-renderer') || climbBlock(like, 5);
                                    how = 'like';
                                }
                            }
                            if (!after && !before) {
                                var h1 = document.querySelector('h1');
                                if (h1 && (h1.textContent || '').trim().length > 0) {
                                    after = climbBlock(h1, 6);
                                    how = 'title-block';
                                }
                            }
                            if (!after && !before) {
                                var btns = document.querySelectorAll('button, [role="button"], ytm-button-renderer');
                                for (var b = 0; b < btns.length; b++) {
                                    if (btns[b].closest('ytm-player, #player, .html5-video-player')) continue;
                                    if ((btns[b].textContent || '').trim() === 'Subscribe') {
                                        after = climbBlock(btns[b], 6);
                                        how = 'subscribe-block';
                                        break;
                                    }
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
                                var up = firstUpNextHost(vid);
                                if (up) { before = up; how = 'upnext'; }
                            }
                            if (!after && !before) return 'no-anchor';
                            row = buildPill(vid);
                            var parent = before ? before.parentNode : after.parentNode;
                            if (!parent) return 'no-parent';
                            if (before) parent.insertBefore(row, before);
                            else parent.insertBefore(row, after.nextSibling);
                            stats.added++;
                            report({pill: 'added', how: how, vid: vid, stats: stats});
                            return 'added-' + how;
                        } catch (e) {
                            return 'err: ' + (e && e.message || e);
                        }
                    }
                    var r = ensure();
                    if (window._phxPillTimer) clearInterval(window._phxPillTimer);
                    var tries = 0;
                    var timer = setInterval(function() {
                        tries++;
                        var rr = ensure();
                        if (tries === 6 && rr === 'no-anchor' && !stats.censusSent) {
                            stats.censusSent = true;
                            report({pill: 'no-anchor-late', vid: currentVid(), census: census()});
                        }
                        if (tries >= 30) { clearInterval(timer); window._phxPillTimer = null; }
                    }, 1000);
                    window._phxPillTimer = timer;
                    if (!window._phoenixPillObserver && document.body) {
                        try {
                            window._phoenixPillObserver = new MutationObserver(function() {
                                if (window._phxPillPend) return;
                                window._phxPillPend = setTimeout(function() {
                                    window._phxPillPend = null;
                                    ensure();
                                }, 650);
                            });
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

    /** Load a url in this browser (used by the home host fragment). */
    fun navigate(url: String) {
        if (_binding != null) binding.webview.loadUrl(url)
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_HOME = "home_mode"

        fun newInstance(url: String, homeMode: Boolean = false) = BrowserFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, url)
                putBoolean(ARG_HOME, homeMode)
            }
        }
    }
}
