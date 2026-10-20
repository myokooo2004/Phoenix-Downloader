package com.phoenix.downloader.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.phoenix.downloader.model.DownloadItem
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The download engine: a queue with at most [MAX_CONCURRENT] downloads
 * running at once, each a yt-dlp process with a stable process id so it
 * can be paused. State lives in [records] (persisted via DownloadStore),
 * so the Downloads screen shows live progress / speed / ETA, and anything
 * unfinished RESUMES after the app is killed (yt-dlp continues from the
 * partial file — every download uses a stable per-record file prefix).
 *
 * Multi-connection speed comes from aria2c (-x 4 -s 4), as before.
 */
object DownloadEngine {

    private const val MAX_CONCURRENT = 3

    private val scope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null
    private val items = mutableListOf<DownloadItem>()
    private val jobs = mutableMapOf<Long, Job>()
    private val removedIds = mutableSetOf<Long>()

    private val _records = MutableStateFlow<List<DownloadItem>>(emptyList())
    val records: StateFlow<List<DownloadItem>> = _records

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        items.clear()
        items.addAll(DownloadStore.loadAll(context))
        // Anything mid-download when the process died goes back to the
        // queue and resumes automatically.
        var changed = false
        for (it in items) {
            if (it.status == DownloadItem.Status.DOWNLOADING) {
                it.status = DownloadItem.Status.QUEUED
                changed = true
            }
        }
        if (changed) persist()
        emit()
        pump()
        ensureService()
    }

    fun downloadDir(context: Context): File {
        // User-chosen folder (dialog "Change", persisted) wins when usable.
        // Android 11+ blocks raw-path writes to shared storage without
        // all-files access, so PROBE writability — an unusable custom
        // folder must fall back to the default, never fail a download.
        val custom = context.getSharedPreferences("phoenix_downloads", Context.MODE_PRIVATE)
            .getString("custom_dir_path", null)
        if (!custom.isNullOrEmpty()) {
            val dir = File(custom)
            val usable = try {
                if (!dir.exists()) dir.mkdirs()
                val probe = File(dir, ".probe")
                probe.createNewFile() && probe.delete()
            } catch (e: Exception) { false }
            if (usable) return dir
        }
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    @Synchronized
    fun enqueue(context: Context, item: DownloadItem) {
        if (appContext == null) init(context)
        if (item.destPath.isEmpty()) item.destPath = downloadDir(context).absolutePath
        items.add(0, item)
        persist()
        emit()
        pump()
        ensureService()
    }

    @Synchronized
    fun pause(id: Long) {
        val item = items.find { it.id == id } ?: return
        if (item.status != DownloadItem.Status.DOWNLOADING &&
            item.status != DownloadItem.Status.QUEUED
        ) return
        item.status = DownloadItem.Status.PAUSED
        item.speedBps = 0
        item.etaSec = -1
        persist()
        emit()
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId(id)) }
    }

    @Synchronized
    fun resume(id: Long) {
        val item = items.find { it.id == id } ?: return
        if (item.status != DownloadItem.Status.PAUSED &&
            item.status != DownloadItem.Status.FAILED
        ) return
        item.status = DownloadItem.Status.QUEUED
        item.errorMessage = null
        persist()
        emit()
        pump()
        ensureService()
    }

    @Synchronized
    fun cancel(id: Long) {
        val item = items.find { it.id == id } ?: return
        removedIds.add(id)
        items.removeAll { it.id == id }
        persist()
        emit()
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId(id)) }
        // If no job is running, clean the partials right away; a running
        // job cleans them in its finally block.
        if (!jobs.containsKey(id)) {
            deletePartials(item)
            removedIds.remove(id)
        }
        pump()
    }

    @Synchronized
    fun deleteCompleted(id: Long) {
        val item = items.find { it.id == id } ?: return
        items.removeAll { it.id == id }
        persist()
        emit()
        item.filePath?.let { runCatching { File(it).delete() } }
    }

    fun hasActiveWork(): Boolean = synchronized(this) {
        items.any {
            it.status == DownloadItem.Status.DOWNLOADING ||
                it.status == DownloadItem.Status.QUEUED
        }
    }

    /** Average progress of currently-downloading items (0 when none). */
    fun activeProgress(): Int = synchronized(this) {
        val active = items.filter { it.status == DownloadItem.Status.DOWNLOADING }
        if (active.isEmpty()) 0 else active.map { it.progress }.average().toInt()
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private fun processId(id: Long) = "dl-$id"

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "video" }

    /** Stable per-record file prefix: partials are findable across restarts. */
    private fun baseName(item: DownloadItem) = "${sanitize(item.title)} [${item.id}]"

    @Synchronized
    private fun persist() {
        val ctx = appContext ?: return
        DownloadStore.saveAll(ctx, items.toList())
    }

    @Synchronized
    private fun emit() {
        _records.value = items.toList()
    }

    private fun ensureService() {
        val ctx = appContext ?: return
        if (!hasActiveWork()) return
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }
    }

    @Synchronized
    private fun pump() {
        val running = items.count { it.status == DownloadItem.Status.DOWNLOADING }
        if (running >= MAX_CONCURRENT) return
        val next = items
            .filter { it.status == DownloadItem.Status.QUEUED }
            .minByOrNull { it.createdAt } ?: return
        next.status = DownloadItem.Status.DOWNLOADING
        next.errorMessage = null
        persist()
        emit()
        jobs[next.id] = scope.launch { runDownload(next) }
        // More slots may be free (e.g. several queued at once)
        if (running + 1 < MAX_CONCURRENT) pump()
    }

    private suspend fun runDownload(item: DownloadItem) {
        val ctx = appContext ?: return
        val dir = File(item.destPath).apply { mkdirs() }
        val sampler = scope.launch { sampleProgress(item, dir) }
        try {
            YtDlpManager.ensureUpdated()
            val attempts = buildAttempts(ctx, item, dir)
            var lastError: Exception? = null
            var succeeded = false
            for ((label, request) in attempts) {
                if (item.status != DownloadItem.Status.DOWNLOADING) break
                try {
                    CrashLogger.logNonFatal(ctx, "DownloadEngine.attempt",
                        Exception("id=${item.id} attempt=$label format=${item.formatId} client=${item.client} height=${item.height}"))
                    var lastEmit = 0L
                    YoutubeDL.getInstance().execute(request, processId(item.id)) { progress, eta, _ ->
                        if (progress >= 0) item.progress = progress.toInt()
                        if (eta > 0) item.etaSec = eta
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 400) {
                            lastEmit = now
                            emit()
                        }
                    }
                    succeeded = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    CrashLogger.logNonFatal(ctx, "DownloadEngine.attemptFailed",
                        Exception("id=${item.id} attempt=$label: ${e.message}"))
                    if (item.status != DownloadItem.Status.DOWNLOADING) break
                }
            }

            when {
                removedIds.contains(item.id) -> Unit // cancelled; finally cleans up
                item.status == DownloadItem.Status.PAUSED -> {
                    CrashLogger.logNonFatal(ctx, "DownloadEngine.paused", Exception("id=${item.id}"))
                }
                succeeded -> {
                    val produced = findProducedFile(dir, item)
                    if (produced != null) {
                        val clean = cleanTargetFile(dir, produced, item)
                        item.filePath = clean.absolutePath
                        item.progress = 100
                        item.downloadedBytes = clean.length()
                        item.totalBytes = clean.length()
                        item.status = DownloadItem.Status.DONE
                        CrashLogger.logNonFatal(ctx, "DownloadEngine.done",
                            Exception("id=${item.id} file=${clean.name} size=${clean.length()}"))
                        // Copy to the public folder only for the default
                        // location — a user-chosen folder already IS the
                        // final destination.
                        if (File(item.destPath).absolutePath ==
                            (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath
                        ) {
                            copyToPublicDownloads(ctx, clean)
                        }
                    } else {
                        item.status = DownloadItem.Status.FAILED
                        item.errorMessage = "No output file produced"
                        CrashLogger.logNonFatal(ctx, "DownloadEngine.noFile", Exception("id=${item.id}"))
                    }
                }
                else -> {
                    item.status = DownloadItem.Status.FAILED
                    item.errorMessage = lastError?.message ?: "Download failed"
                    CrashLogger.logNonFatal(ctx, "DownloadEngine.failed",
                        Exception("id=${item.id}: ${lastError?.message}"))
                }
            }
        } catch (e: Exception) {
            if (!removedIds.contains(item.id) && item.status == DownloadItem.Status.DOWNLOADING) {
                item.status = DownloadItem.Status.FAILED
                item.errorMessage = e.message
            }
            CrashLogger.logNonFatal(ctx, "DownloadEngine.error", e)
        } finally {
            sampler.cancel()
            item.speedBps = 0
            item.etaSec = -1
            synchronized(this) {
                jobs.remove(item.id)
                if (removedIds.remove(item.id)) deletePartials(item)
                persist()
                emit()
            }
            pump()
        }
    }

    /** Samples partial-file growth once a second -> speed (EMA) + bytes + ETA. */
    private suspend fun sampleProgress(item: DownloadItem, dir: File) {
        var lastBytes = -1L
        var lastTime = System.currentTimeMillis()
        var ticks = 0
        while (true) {
            delay(1000)
            if (item.status != DownloadItem.Status.DOWNLOADING) continue
            // Match by the unique "[id]" marker, NOT the title prefix:
            // yt-dlp may normalize title characters in on-disk names, which
            // silently broke prefix matching in the field (0 B shown while
            // the notification already had real progress).
            val bytes = dir.listFiles()
                ?.filter { it.name.contains("[${item.id}]") && !it.name.endsWith(".aria2") }
                ?.sumOf { it.length() } ?: 0L
            val now = System.currentTimeMillis()
            if (lastBytes >= 0 && now > lastTime) {
                val inst = (bytes - lastBytes) * 1000 / (now - lastTime)
                item.speedBps = if (item.speedBps == 0L) inst.coerceAtLeast(0)
                else (item.speedBps * 0.6 + inst.coerceAtLeast(0) * 0.4).toLong()
            }
            lastBytes = bytes
            lastTime = now
            item.downloadedBytes = bytes
            if (item.progress in 3..99 && bytes > 0) {
                val est = bytes * 100 / item.progress
                if (est > item.totalBytes) item.totalBytes = est
                if (item.speedBps > 0 && item.totalBytes > bytes) {
                    item.etaSec = (item.totalBytes - bytes) / item.speedBps
                }
            }
            ticks++
            if (ticks % 4 == 0) persist()
            emit()
        }
    }

    private fun deletePartials(item: DownloadItem) {
        val dir = File(item.destPath)
        dir.listFiles()?.filter { it.name.contains("[${item.id}]") }?.forEach {
            runCatching { it.delete() }
        }
    }

    private fun findProducedFile(dir: File, item: DownloadItem): File? {
        return dir.listFiles()
            ?.filter {
                it.name.contains("[${item.id}]") &&
                    !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                    !it.name.endsWith(".temp") && !it.name.endsWith(".aria2") &&
                    // Not a DASH fragment ("Title [id].f401.mp4")
                    !it.name.contains(Regex("\\.f\\d+\\."))
            }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Renames "Title [id].ext" to the clean "Title.ext" (dedupe with (n)). */
    private fun cleanTargetFile(dir: File, produced: File, item: DownloadItem): File {
        val ext = produced.extension.ifEmpty { if (item.isAudio) "mp3" else "mp4" }
        var target = File(dir, "${sanitize(item.title)}.$ext")
        var n = 1
        while (target.exists() && target.absolutePath != produced.absolutePath) {
            target = File(dir, "${sanitize(item.title)} ($n).$ext")
            n++
        }
        return if (target.absolutePath == produced.absolutePath) produced
        else if (produced.renameTo(target)) target else produced
    }

    // ---------------------------------------------------------------
    // yt-dlp request building (ported from the old DownloadService,
    // including the multi-client retry matrix)
    // ---------------------------------------------------------------

    private fun baseRequest(context: Context, item: DownloadItem, dir: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(item.url)
        // Cookies for bot-check bypass (rotating WebView cookies)
        CookieHelper.getCookieFilePath(context)?.let { cookiePath ->
            request.addOption("--cookies", cookiePath)
        }
        request.addOption("-o", File(dir, "${baseName(item)}.%(ext)s").absolutePath)
        request.addOption("--no-mtime")
        // aria2c multi-connection: 4 connections, 4 splits (fast)
        request.addOption("--external-downloader", "aria2c")
        request.addOption("--external-downloader-args", "aria2c:-x 4 -s 4 -k 1M --summary-interval=0")
        return request
    }

    private fun buildAttempts(
        context: Context,
        item: DownloadItem,
        dir: File
    ): List<Pair<String, YoutubeDLRequest>> {
        fun clientArg(r: YoutubeDLRequest, client: String?) {
            if (client != null) r.addOption("--extractor-args", "youtube:player_client=$client")
        }
        val attempts = mutableListOf<Pair<String, YoutubeDLRequest>>()
        if (item.isAudio) {
            val kbps = item.formatId.removePrefix("MP3_").toIntOrNull() ?: 192
            for ((label, client) in listOf(
                "mp3-dialog" to item.client, "mp3-tv" to "tv", "mp3-plain" to null
            )) {
                val r = baseRequest(context, item, dir)
                clientArg(r, client)
                r.addOption("-f", "bestaudio/best")
                r.addOption("--extract-audio")
                r.addOption("--audio-format", "mp3")
                r.addOption("--audio-quality", "${kbps}K")
                attempts.add(label to r)
            }
        } else {
            val heightSel =
                "bestvideo[height<=${item.height}]+bestaudio/best[height<=${item.height}]"
            attempts.add("exact" to baseRequest(context, item, dir).apply {
                clientArg(this, item.client)
                addOption("-f", item.formatId)
                addOption("--merge-output-format", item.container.lowercase())
            })
            for ((label, client) in listOf(
                "height-tv" to "tv",
                "height-web_safari" to "web_safari",
                "height-default" to null
            )) {
                attempts.add(label to baseRequest(context, item, dir).apply {
                    clientArg(this, client)
                    addOption("-f", heightSel)
                    addOption("--merge-output-format", item.container.lowercase())
                })
            }
        }
        return attempts
    }

    // ---------------------------------------------------------------
    // Public Download folder copy (best effort, API 29+ MediaStore)
    // ---------------------------------------------------------------

    private fun copyToPublicDownloads(context: Context, file: File) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val mime = when (file.extension.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "mkv" -> "video/x-matroska"
                    else -> "video/mp4"
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/PhoenixDownloader"
                    )
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dest = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "PhoenixDownloader"
                ).apply { mkdirs() }
                file.copyTo(File(dest, file.name), overwrite = true)
            }
        } catch (e: Exception) {
            CrashLogger.logNonFatal(context, "DownloadEngine.mediaStore", e)
        }
    }

    /** Summary notification content for the foreground service. */
    fun notificationText(): Pair<String, String> = synchronized(this) {
        val active = items.filter { it.status == DownloadItem.Status.DOWNLOADING }
        val queued = items.count { it.status == DownloadItem.Status.QUEUED }
        if (active.isEmpty()) return "Phoenix Downloader" to "Preparing downloads…"
        val first = active.first()
        val more = (active.size - 1) + queued
        val title = "Downloading ${active.size + queued} file(s)"
        val text = "${first.title} — ${first.progress}%" +
            if (more > 0) " (+$more more)" else ""
        title to text
    }
}
