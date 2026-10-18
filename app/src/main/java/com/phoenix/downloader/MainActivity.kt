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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        if (savedInstanceState == null) showHome()

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showHome(); true }
                R.id.nav_downloads -> { showDownloads(); true }
                else -> false
            }
        }
    }

    fun showHome() {
        supportFragmentManager.beginTransaction().replace(R.id.nav_host, HomeFragment()).commit()
    }

    fun showDownloads() {
        supportFragmentManager.beginTransaction().replace(R.id.nav_host, DownloadsFragment()).commit()
    }

    fun openPlayer(url: String, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, PlayerFragment.newInstance(url, title))
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
