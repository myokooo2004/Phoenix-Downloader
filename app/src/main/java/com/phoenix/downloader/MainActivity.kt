package com.phoenix.downloader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.phoenix.downloader.databinding.ActivityMainBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
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
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    fun showDownloads() {
        showFragment(DownloadsFragment(), addToBackStack = false)
        binding.bottomNav.selectedItemId = R.id.nav_downloads
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
}
