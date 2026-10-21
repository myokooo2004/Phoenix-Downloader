package com.phoenix.downloader.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground-service shell for [DownloadEngine]: keeps the process alive
 * while downloads run (with a wake lock) and mirrors engine state into a
 * persistent notification. All download logic lives in the engine, which
 * supports a queue of up to 3 concurrent downloads with pause/resume.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // If the process was restarted by the system, reload engine state
        // (unfinished downloads re-queue and resume from partial files).
        DownloadEngine.init(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (title, text) = DownloadEngine.notificationText()
        val notification = buildNotification(title, text, DownloadEngine.activeProgress())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
        if (!monitorStarted) {
            monitorStarted = true
            serviceScope.launch {
                // Keep the notification fresh while work exists, then stop.
                // Only re-post when something actually changed - re-posting
                // every 2s made the shade entry churn/flicker.
                var lastTitle: String? = null
                var lastText: String? = null
                var lastProgress = -1
                while (DownloadEngine.hasActiveWork()) {
                    delay(2000)
                    val (t, x) = DownloadEngine.notificationText()
                    val p = DownloadEngine.activeProgress()
                    if (t != lastTitle || x != lastText || p != lastProgress) {
                        lastTitle = t; lastText = x; lastProgress = p
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(NOTIFICATION_ID, buildNotification(t, x, p))
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoenixDownloader:downloads")
        @Suppress("DEPRECATION")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int
    ): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "downloads"
    }
}
