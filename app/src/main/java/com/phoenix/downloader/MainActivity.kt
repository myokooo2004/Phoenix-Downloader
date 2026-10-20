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
import com.phoenix.downloader.ui.home.HomeFragment
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
        supportFragmentManager.beginTransaction().replace(R.id.nav_host, HomeFragment()).commit()
        if (binding.bottomNav.selectedItemId != R.id.nav_home) {
            suppressNavListener = true
            binding.bottomNav.selectedItemId = R.id.nav_home
            suppressNavListener = false
        }
    }

    fun showDownloads() {
        supportFragmentManager.beginTransaction().replace(R.id.nav_host, DownloadsFragment()).commit()
        if (binding.bottomNav.selectedItemId != R.id.nav_downloads) {
            suppressNavListener = true
            binding.bottomNav.selectedItemId = R.id.nav_downloads
            suppressNavListener = false
        }
    }

    fun openPlayer(url: String, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, PlayerFragment.newInstance(url, title))
            .addToBackStack(null)
            .commit()
    }

    fun openVideoDetail(url: String, title: String, thumbnail: String = "", duration: Int = 0) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, com.phoenix.downloader.ui.detail.VideoDetailFragment.newInstance(url, title, thumbnail, duration))
            .addToBackStack(null)
            .commit()
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
