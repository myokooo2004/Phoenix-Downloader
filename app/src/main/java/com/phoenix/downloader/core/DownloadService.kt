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
                CrashLogger.logNonFatal(
                    this@DownloadService, "DownloadService",
                    Exception("start url=$url format=$formatId title=$title")
                )
                val outputDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
                val outputTemplate = "$outputDir/$safeTitle.%(ext)s"

                val request = YoutubeDLRequest(url)
                // Cookies (WebView-synced) — same as YtDlpManager.baseRequest.
                // Without them the actual download hit bot-checks that the
                // format fetch (which HAS cookies) passed.
                CookieHelper.getCookieFilePath(this@DownloadService)?.let { cookiePath ->
                    request.addOption("--cookies", cookiePath)
                }
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

                // Locate the produced file (skip yt-dlp temp/part files)
                val produced = File(outputDir).listFiles()
                    ?.filter {
                        it.name.startsWith(safeTitle) &&
                            !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                            !it.name.endsWith(".temp") && it.length() > 0
                    }
                    ?.maxByOrNull { it.lastModified() }
                if (produced != null) {
                    val publicPath = copyToPublicDownloads(produced)
                    CrashLogger.logNonFatal(
                        this@DownloadService, "DownloadService",
                        Exception("success file=${produced.name} size=${produced.length()} public=$publicPath")
                    )
                } else {
                    CrashLogger.logNonFatal(
                        this@DownloadService, "DownloadService",
                        Exception("execute finished but NO output file found in $outputDir (format=$formatId)")
                    )
                }

                updateNotification("$title: Complete", 100)
            } catch (e: Exception) {
                CrashLogger.logNonFatal(this@DownloadService, "DownloadService", e)
                updateNotification("$title: Failed", 0)
            } finally {
                // DETACH keeps the final status notification visible after stop
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Copies a finished file into the public Downloads folder
     * (Download/PhoenixDownloader) so it is visible in the Files app,
     * not only inside the app's private directory.
     * Returns a short status string for logging.
     */
    private fun copyToPublicDownloads(src: File): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mime = when (src.extension.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "m4a" -> "audio/mp4"
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    else -> "video/mp4"
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/PhoenixDownloader"
                    )
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return "mediastore insert returned null"
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                "mediastore ok"
            } else {
                @Suppress("DEPRECATION")
                val pubDir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), "PhoenixDownloader"
                )
                pubDir.mkdirs()
                src.copyTo(File(pubDir, src.name), overwrite = true)
                "legacy copy ok"
            }
        } catch (e: Exception) {
            "copy failed: ${e.message}"
        }
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
