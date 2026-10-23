package com.phoenix.downloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phoenix.downloader.databinding.ActivityMainBinding
import com.phoenix.downloader.ui.downloads.DownloadsFragment
import com.phoenix.downloader.ui.player.PlayerFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var suppressNavListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Crash logger first (catches everything after this point)
        com.phoenix.downloader.core.CrashLogger.init(this)
        super.onCreate(savedInstanceState)
        // yt-dlp init (must be called or isReady() stays false).
        // Failures are surfaced to the user, never silent (see YtDlpManager.getInitError).
        com.phoenix.downloader.core.YtDlpManager.init(this)
        com.phoenix.downloader.core.YtDlpManager.getInitError()?.let { err ->
            android.util.Log.e("MainActivity", "YtDlpManager init failed", err)
            android.widget.Toast.makeText(
                this,
                "yt-dlp init failed: ${err.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Back behavior: pushed screens pop normally; from My Files go back
        // to Home (top-level screens are replace()d without a back stack, so
        // the default back press would exit the app from My Files).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    supportFragmentManager.backStackEntryCount > 0 ->
                        supportFragmentManager.popBackStack()
                    supportFragmentManager.findFragmentById(R.id.nav_host) is DownloadsFragment ->
                        showHome()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        // Download engine: reloads persisted records; unfinished downloads
        // re-queue and resume automatically.
        com.phoenix.downloader.core.DownloadEngine.init(this)

        requestPermissions()
        if (savedInstanceState == null) showHome()

        // Background: refresh YouTube cookies for yt-dlp bot bypass.
        // Loads youtube.com in a hidden WebView, saves cookies, then cleans up.
        refreshYouTubeCookies()

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (suppressNavListener) return@setOnItemSelectedListener false
            when (item.itemId) {
                R.id.nav_home -> { showHome(); true }
                R.id.nav_downloads -> { showDownloads(); true }
                R.id.nav_files -> { showDownloads(com.phoenix.downloader.ui.downloads.DownloadsFragment.MODE_DONE); true }
                else -> false
            }
        }

        // The floating bottom bar belongs to the top-level screens only.
        // On pushed screens (player, detail, search, browser) it overlaps
        // content and player controls — hide it whenever the back stack
        // is non-empty, restore it when back at Home / My Files.
        supportFragmentManager.addOnBackStackChangedListener {
            binding.bottomNav.visibility =
                if (supportFragmentManager.backStackEntryCount == 0) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
        }
    }

    fun showHome() {
        // Home is the browser-base screen since 2026-10-22 (user
        // spec): our top bar over YouTube itself in the in-app
        // browser.
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, com.phoenix.downloader.ui.home.HomeBrowserFragment())
            .commit()
        if (binding.bottomNav.selectedItemId != R.id.nav_home) {
            suppressNavListener = true
            binding.bottomNav.selectedItemId = R.id.nav_home
            suppressNavListener = false
        }
    }

    /**
     * Land on Home with the embedded browser already navigating to
     * [url] (search submits: a query becomes YouTube's own results
     * page — YouTube's flow, not a native list). Pops any pushed
     * screen (e.g. the search entry) first.
     */
    fun showHomeWithUrl(url: String) {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }
        val current = supportFragmentManager.findFragmentById(R.id.nav_host)
        if (current is com.phoenix.downloader.ui.home.HomeBrowserFragment) {
            current.navigate(url)
        } else {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.nav_host,
                    com.phoenix.downloader.ui.home.HomeBrowserFragment.newInstance(url)
                )
                .commit()
        }
        if (binding.bottomNav.selectedItemId != R.id.nav_home) {
            suppressNavListener = true
            binding.bottomNav.selectedItemId = R.id.nav_home
            suppressNavListener = false
        }
    }

    /**
     * The DOWNLOAD tab and the home top bar's downloads button are
     * the same destination (user, 2026-10-23): in-progress downloads.
     * MY FILES is the same screen in its completed-only mode.
     */
    fun showDownloads(mode: Int = DownloadsFragment.MODE_ACTIVE) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, DownloadsFragment.newInstance(mode))
            .commit()
        val itemId = if (mode == DownloadsFragment.MODE_DONE) R.id.nav_files else R.id.nav_downloads
        if (binding.bottomNav.selectedItemId != itemId) {
            suppressNavListener = true
            binding.bottomNav.selectedItemId = itemId
            suppressNavListener = false
        }
    }

    fun openBrowser(url: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, com.phoenix.downloader.ui.browser.BrowserFragment.newInstance(url))
            .addToBackStack(null)
            .commit()
    }

    fun openSearch(query: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, com.phoenix.downloader.ui.search.SearchFragment.newInstance(query))
            .addToBackStack(null)
            .commit()
    }

    /**
     * The query of the YouTube search currently shown in the home
     * browser, if any — the search entry field mirrors it, so the
     * place the user searched always holds the current YouTube
     * search (user spec 2026-10-23).
     */
    fun currentSearchQuery(): String? {
        val home = supportFragmentManager.findFragmentById(R.id.nav_host)
            as? com.phoenix.downloader.ui.home.HomeBrowserFragment ?: return null
        val url = home.currentUrl() ?: return null
        if (!url.contains("/results")) return null
        val q = android.net.Uri.parse(url).getQueryParameter("search_query")
        return q?.takeIf { it.isNotEmpty() }
    }

    /** The search entry screen (history + hot searches + live suggestions). */
    fun openSearchEntry() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, com.phoenix.downloader.ui.search.SearchEntryFragment())
            .addToBackStack(null)
            .commit()
    }

    /** Play a file already on the device (from My Files). */
    fun openLocalPlayer(path: String, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, PlayerFragment.newInstance(path, title, isLocal = true))
            .addToBackStack(null)
            .commit()
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(perms.toTypedArray())
        }
    }

    /**
     * Background YouTube cookie refresh for yt-dlp bot bypass.
     * Loads youtube.com in a hidden WebView, saves cookies via CookieHelper,
     * then destroys the WebView. Runs once per app start, silent on failure.
     */
    private fun refreshYouTubeCookies() {
        try {
            val webView = android.webkit.WebView(this)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            // Use a modern user agent to avoid bot detection
            webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    try {
                        com.phoenix.downloader.core.CookieHelper.saveCookiesForUrl(
                            this@MainActivity,
                            "https://www.youtube.com"
                        )
                        android.util.Log.d("MainActivity", "Background YouTube cookies refreshed")
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "Cookie refresh failed", e)
                    } finally {
                        // Clean up after a short delay to ensure cookies are written
                        view?.postDelayed({
                            try {
                                view.destroy()
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }, 2000)
                    }
                }

                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // Silent - cookie refresh is best-effort
                    try {
                        view?.destroy()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            // Load in background - WebView is never attached to UI
            webView.loadUrl("https://www.youtube.com/")
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Background cookie refresh init failed", e)
        }
    }
}
