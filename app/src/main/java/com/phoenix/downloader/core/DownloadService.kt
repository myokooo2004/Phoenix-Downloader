package com.phoenix.downloader.core

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("URL") ?: return START_NOT_STICKY
        val formatId = intent.getStringExtra("FORMAT_ID") ?: "best"
        val title = intent.getStringExtra("TITLE") ?: "Downloading..."

        startForeground(1, buildNotification("Preparing...", 0))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outputDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
                val outputTemplate = "$outputDir/$safeTitle.%(ext)s"

                val request = YoutubeDLRequest(url)
                if (formatId.startsWith("MP3_")) {
                    // MP3 extraction via ffmpeg
                    val kbps = formatId.removePrefix("MP3_")
                    request.addOption("-f", "bestaudio/best")
                    request.addOption("--extract-audio")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", kbps)
                } else {
                    // Video: check for MKV container request (formatId|MKV)
                    val isMkv = formatId.endsWith("|MKV")
                    val baseFormatId = formatId.removeSuffix("|MKV")
                    // For video formats, merge with best audio using ffmpeg
                    // Format: "<videoId>+bestaudio/best" ensures audio is included
                    val formatSelector = if (baseFormatId != "best" && !baseFormatId.contains("+")) {
                        "$baseFormatId+bestaudio/best"
                    } else {
                        baseFormatId
                    }
                    request.addOption("-f", formatSelector)
                    request.addOption("--merge-output-format", if (isMkv) "mkv" else "mp4")
                }
                // Use aria2c for multi-connection faster downloads (4 connections to avoid blocks)
                request.addOption("--external-downloader", "aria2c")
                request.addOption("--external-downloader-args", "aria2c:-x 4 -s 4 -k 1M")
                request.addOption("-o", outputTemplate)
                request.addOption("--no-check-certificate")
                request.addOption("--socket-timeout", "30")

                updateNotification("$title: 0%", 0)

                YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                    val pct = progress.toInt().coerceIn(0, 100)
                    updateNotification("$title: $pct%", pct)
                }

                updateNotification("$title: Complete", 100)
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("$title: Failed", 0)
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
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
