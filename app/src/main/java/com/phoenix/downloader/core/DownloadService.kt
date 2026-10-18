package com.phoenix.downloader.core

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("URL") ?: return START_NOT_STICKY
        val formatId = intent.getStringExtra("FORMAT_ID") ?: "best"
        val title = intent.getStringExtra("TITLE") ?: "Downloading..."

        startForeground(1, buildNotification("Preparing...", 0))

        CoroutineScope(Dispatchers.IO).launch {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            val py = Python.getInstance()
            val outputDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            val ffmpegPath = extractFFmpeg() 

            val callback = object : java.lang.Object() {
                fun onProgress(percent: Float) {
                    updateNotification("$title: ${percent.toInt()}%", percent.toInt())
                }
            }
            
            py.getModule("ytdlp_bridge").callAttr("download_video", url, formatId, outputDir, ffmpegPath, callback)
            
            stopForeground(true)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun extractFFmpeg(): String {
        val ffmpegFile = File(filesDir, "ffmpeg")
        if (!ffmpegFile.exists()) {
            try {
                assets.open("ffmpeg").use { input ->
                    FileOutputStream(ffmpegFile).use { output -> input.copyTo(output) }
                }
                ffmpegFile.setExecutable(true)
            } catch (e: Exception) { e.printStackTrace() }
        }
        return ffmpegFile.absolutePath
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val channelId = "download_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Phoenix Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text, progress))
    }
}
