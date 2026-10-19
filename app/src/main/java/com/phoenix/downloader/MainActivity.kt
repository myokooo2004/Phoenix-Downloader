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

        requestPermissions()
        if (savedInstanceState == null) showHome()

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (suppressNavListener) return@setOnItemSelectedListener false
            when (item.itemId) {
                R.id.nav_home -> { showHome(); true }
                R.id.nav_downloads -> { showDownloads(); true }
                else -> false
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
}
