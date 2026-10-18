package com.phoenix.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phoenix.downloader.databinding.ActivityMainBinding
import com.phoenix.downloader.R
import com.phoenix.downloader.ui.browser.BrowserFragment
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import com.phoenix.downloader.ui.downloads.DownloadsFragment
import com.phoenix.downloader.ui.home.HomeFragment
import com.phoenix.downloader.ui.player.PlayerFragment
import com.phoenix.downloader.ui.video.VideoOptionsDialogFragment

/**
 * Single-activity host. Bottom nav switches Home <-> Downloads;
 * browser / player are pushed on the back stack; the download
 * quality sheet is a BottomSheetDialogFragment.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var suppressNavListener = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* granted or not — app continues, features degrade gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Crash logger first (catches everything after this point)
        com.phoenix.downloader.core.CrashLogger.init(this)

        // Apply saved language before super.onCreate
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val lang = prefs.getString("language", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize yt-dlp on background thread (extracts Python env, takes seconds)
        Thread {
            try {
                com.phoenix.downloader.core.YtDlpManager.init(this)
                // Check for yt-dlp updates after init (fixes YouTube compatibility)
                kotlinx.coroutines.runBlocking {
                    com.phoenix.downloader.core.YtDlpManager.checkForUpdates()
                }
                com.phoenix.downloader.core.YtDlpManager.markUpdateChecked()
            } catch (e: Exception) {
                e.printStackTrace()
                // Mark checked even on failure so app doesn't block forever
                com.phoenix.downloader.core.YtDlpManager.markUpdateChecked()
            }
        }.start()

        requestAppPermissions()

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (suppressNavListener) return@setOnItemSelectedListener false
            when (item.itemId) {
                R.id.nav_home -> {
                    showHome()
                    true
                }
                R.id.nav_downloads -> {
                    showDownloads()
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            val shared = extractSharedText(intent)
            showFragment(HomeFragment.newInstance(shared), addToBackStack = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedText(intent)?.let { text ->
            showFragment(HomeFragment.newInstance(text), addToBackStack = false)
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
    }

    private fun extractSharedText(intent: Intent): String? {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        }
        return null
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment, addToBackStack: Boolean) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment)
            .apply { if (addToBackStack) addToBackStack(null) }
            .commit()
    }

    fun showHome() {
        showFragment(HomeFragment(), addToBackStack = false)
        // Suppress listener to avoid infinite recursion via setSelectedItemId
        if (binding.bottomNav.selectedItemId != R.id.nav_home) {
            suppressNavListener = true
            binding.bottomNav.selectedItemId = R.id.nav_home
            suppressNavListener = false
        }
    }

    fun showDownloads() {
        showFragment(DownloadsFragment(), addToBackStack = false)
        // Suppress listener to avoid infinite recursion via setSelectedItemId
        if (binding.bottomNav.selectedItemId != R.id.nav_downloads) {
            suppressNavListener = true
            binding.bottomNav.selectedItemId = R.id.nav_downloads
            suppressNavListener = false
        }
    }

    fun openBrowser(url: String) {
        showFragment(BrowserFragment.newInstance(url), addToBackStack = true)
    }

    /** Step 2 of the URL flow: quality bottom sheet. */
    fun openDownloadDialog(videoUrl: String, title: String) {
        DownloadDialogFragment.newInstance(videoUrl, title)
            .show(supportFragmentManager, "download_dialog")
    }

    /** Thumbnail tap: Watch / Download options. */
    fun showVideoOptions(videoUrl: String, title: String) {
        VideoOptionsDialogFragment.newInstance(videoUrl, title)
            .show(supportFragmentManager, "video_options")
    }

    /** In-app playback of a remote stream (resolved via yt-dlp). */
    fun openPlayerStream(videoUrl: String, title: String) {
        showFragment(PlayerFragment.newStreamInstance(videoUrl, title), addToBackStack = true)
    }

    /** In-app playback of a downloaded file (incl. local 4K MP4). */
    fun openPlayerLocal(localPath: String, title: String) {
        showFragment(PlayerFragment.newLocalInstance(localPath, title), addToBackStack = true)
    }

    /**
     * Request storage + notification permissions upfront at first launch.
     * Storage: READ_MEDIA_VIDEO/AUDIO on API 33+, READ_EXTERNAL_STORAGE below.
     * Notifications: POST_NOTIFICATIONS on API 33+ for download progress.
     */
    private fun requestAppPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}
