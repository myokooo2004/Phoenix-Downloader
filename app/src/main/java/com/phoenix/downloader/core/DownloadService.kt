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
        val client = intent.getStringExtra("CLIENT")
        val height = intent.getIntExtra("HEIGHT", 0)
        val container = intent.getStringExtra("CONTAINER") ?: "MP4"

        startForeground(1, buildNotification("Preparing...", 0))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Make sure yt-dlp is the latest before downloading (user rule:
                // never an old version — old solvers fail YouTube's n-challenge).
                com.phoenix.downloader.core.YtDlpManager.ensureUpdated()
                CrashLogger.logNonFatal(
                    this@DownloadService, "DownloadService",
                    Exception("start url=$url format=$formatId client=${client ?: "default"} title=$title")
                )
                val outputDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
                val outputTemplate = "$outputDir/$safeTitle.%(ext)s"

                // ---- Retry matrix -------------------------------------------------
                // Field-proven failure (build 246 log): the default/web client's
                // format URLs are SABR/PO-token gated -> the download dies with
                // "HTTP Error 403" even though listing worked. So: attempt 1 =
                // the exact format the user picked; later attempts re-resolve
                // the same height through token-free clients (tv, web_safari,
                // default). First success wins; every attempt is logged.
                val attempts = buildAttempts(formatId, client, height, container)
                var lastError: Exception? = null
                var succeeded = false
                for ((index, attempt) in attempts.withIndex()) {
                    try {
                        CrashLogger.logNonFatal(
                            this@DownloadService, "DownloadService",
                            Exception(
                                "attempt ${index + 1}/${attempts.size} client=${attempt.client ?: "default"} " +
                                    "selector=${attempt.selector} mp3=${attempt.mp3Kbps}"
                            )
                        )
                        val request = YoutubeDLRequest(url)
                        if (!attempt.client.isNullOrEmpty()) {
                            request.addOption("--extractor-args", "youtube:player_client=${attempt.client}")
                        }
                        // Cookies (WebView-synced) — same as YtDlpManager.baseRequest.
                        CookieHelper.getCookieFilePath(this@DownloadService)?.let { cookiePath ->
                            request.addOption("--cookies", cookiePath)
                        }
                        request.addOption("-f", attempt.selector)
                        if (attempt.mp3Kbps != null) {
                            request.addOption("--extract-audio")
                            request.addOption("--audio-format", "mp3")
                            request.addOption("--audio-quality", attempt.mp3Kbps)
                        } else {
                            request.addOption(
                                "--merge-output-format",
                                if (container == "MKV") "mkv" else "mp4"
                            )
                        }
                        // aria2c multi-connection (4 connections to avoid blocks)
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
                        succeeded = true
                        break
                    } catch (e: Exception) {
                        lastError = e
                        CrashLogger.logNonFatal(
                            this@DownloadService, "DownloadService",
                            Exception(
                                "attempt ${index + 1} failed client=${attempt.client ?: "default"}: " +
                                    (e.message ?: "").take(500)
                            )
                        )
                    }
                }
                if (!succeeded) {
                    throw lastError ?: Exception("All download attempts failed")
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

    private data class Attempt(
        val client: String?,
        val selector: String,
        val mp3Kbps: String?
    )

    /**
     * Attempt 1 is always exactly what the user picked (same client + format).
     * Fallbacks re-resolve the same target through other clients, because the
     * default/web client's URLs are SABR/PO-token gated (HTTP 403 on download).
     */
    private fun buildAttempts(
        formatId: String,
        dialogClient: String?,
        height: Int,
        container: String
    ): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        fun add(client: String?, selector: String, mp3Kbps: String?) {
            val a = Attempt(client, selector, mp3Kbps)
            if (!attempts.contains(a)) attempts.add(a)
        }
        if (formatId.startsWith("MP3_")) {
            val kbps = formatId.removePrefix("MP3_")
            add(dialogClient, "bestaudio/best", kbps)
            add("tv", "bestaudio/best", kbps)
            add(null, "bestaudio/best", kbps)
        } else {
            val baseFormatId = formatId.removeSuffix("|MKV")
            val exactSelector = if (baseFormatId != "best" && !baseFormatId.contains("+")) {
                "$baseFormatId+bestaudio/best"
            } else {
                baseFormatId
            }
            add(dialogClient, exactSelector, null)
            val heightSelector = if (height > 0) {
                "bestvideo[height<=$height]+bestaudio/best[height<=$height]"
            } else {
                "bestvideo+bestaudio/best"
            }
            add("tv", heightSelector, null)
            add("web_safari", heightSelector, null)
            add(null, heightSelector, null)
        }
        return attempts
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
