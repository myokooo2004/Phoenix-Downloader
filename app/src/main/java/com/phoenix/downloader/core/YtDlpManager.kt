package com.phoenix.downloader.core

import android.content.Context
import com.phoenix.downloader.model.FormatOption
import com.phoenix.downloader.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wrapper around yt-dlp via the youtubedl-android library.
 * Call [init] once at app start before use.
 */
object YtDlpManager {

    /**
     * User rule (standing): NEVER run an old yt-dlp — always update to the
     * latest stable at startup. The bundled 2025.11.12's JS-challenge solver
     * can no longer solve YouTube's current player ("found 0 n function
     * possibilities" -> formats missing / HTTP 403), so this stays ON.
     */
    private const val AUTO_UPDATE = true

    private var initialized = false
    private var updateChecked = false
    private var appContext: Context? = null

    /**
     * yt-dlp version BAKED INTO the APK as res/raw/ytdlp (the app module's raw
     * resource shadows the library's, so the library extracts THIS file on
     * first init). User order 2026-10-20: replace the bundled version
     * directly with the latest — no old version, ever. The runtime self-update
     * (AUTO_UPDATE) can still advance beyond this floor afterwards.
     */
    private const val BUNDLED_YTDLP_VERSION = "2026.08.19"

    /** Set when init() throws; the real reason fetches fail. */
    private var initError: Throwable? = null

    fun init(context: Context) {
        if (initialized) return
        try {
            appContext = context.applicationContext
            // One-time purge per BUNDLED_YTDLP_VERSION change: installs made
            // from older APKs already extracted the OLD yt-dlp into
            // noBackupFilesDir, and the library only extracts res/raw/ytdlp
            // when the binary is missing. Delete the extracted dir so the
            // baked-in 2026.08.19 is extracted on this init. Runs BEFORE
            // YoutubeDL.init; the marker makes later launches (and any newer
            // self-updated version) untouched.
            try {
                val prefs = appContext!!.getSharedPreferences("phoenix_ytdlp", Context.MODE_PRIVATE)
                if (prefs.getString("bundled_ytdlp_version", null) != BUNDLED_YTDLP_VERSION) {
                    val dir = java.io.File(
                        appContext!!.noBackupFilesDir, "youtubedl-android/yt-dlp"
                    )
                    if (dir.exists()) dir.deleteRecursively()
                    prefs.edit()
                        .putString("bundled_ytdlp_version", BUNDLED_YTDLP_VERSION)
                        .apply()
                    logNote(
                        "YtDlpManager.bundled",
                        "purged extracted yt-dlp; baked-in version $BUNDLED_YTDLP_VERSION will be extracted"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("YtDlpManager", "bundled yt-dlp purge failed", e)
            }
            YoutubeDL.getInstance().init(appContext!!)
            // Initialize FFmpeg for audio/video merging (4K downloads)
            try {
                com.yausername.ffmpeg.FFmpeg.getInstance().init(appContext!!)
            } catch (e: Exception) {
                // FFmpeg init optional, log but don't fail
                android.util.Log.w("YtDlpManager", "FFmpeg init failed", e)
            }
            // Initialize Aria2c for multi-connection downloads
            try {
                com.yausername.aria2c.Aria2c.getInstance().init(appContext!!)
            } catch (e: Exception) {
                android.util.Log.w("YtDlpManager", "Aria2c init failed", e)
            }
            initialized = true
            updateChecked = true
            initError = null
            logNote("YtDlpManager.version", "yt-dlp version after init: ${getYtDlpVersion()}")
            if (AUTO_UPDATE) {
                CoroutineScope(Dispatchers.IO).launch {
                    // Update FIRST, then diagnose, so the verbose log shows the
                    // version that will actually serve fetches/downloads.
                    ensureUpdated()
                    runVerboseDiagnostic()
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    runVerboseDiagnostic()
                }
            }
        } catch (e: Exception) {
            initError = e
            logFailure("YtDlpManager.init", e)
        }
    }

    fun isInitialized(): Boolean = initialized

    /**
     * Runs yt-dlp --verbose once and logs the debug header lines that state the
     * Python version and the JS runtimes in use, plus any qjs/python files found
     * in the native lib dir. Pure diagnostics — result lands in crash.log.
     */
    private suspend fun runVerboseDiagnostic() {
        try {
            val ctx = appContext ?: return
            val nativeDir = ctx.applicationInfo.nativeLibraryDir ?: ""
            val libs = try {
                java.io.File(nativeDir).listFiles()
                    ?.map { it.name }
                    ?.filter { it.contains("qjs") || it.contains("python") }
                    ?.joinToString(", ") ?: "none"
            } catch (e: Exception) {
                "list failed: ${e.message}"
            }
            logNote("YtDlpDiag.libs", "nativeLibDir=$nativeDir libs=[$libs]")

            val req = YoutubeDLRequest("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            req.addOption("--verbose")
            req.addOption("--simulate")
            req.addOption("--no-download")
            var text = ""
            try {
                val resp = YoutubeDL.getInstance().execute(req)
                text = (resp.out ?: "") + "\n" + (resp.err ?: "")
            } catch (e: Exception) {
                // yt-dlp writes the debug header to stderr before failing;
                // the library folds stderr into the exception message.
                text = e.message ?: ""
            }
            val interesting = text.lineSequence()
                .map { it.trim() }
                .filter {
                    it.contains("Python", ignoreCase = true) ||
                        it.contains("JS runtime", ignoreCase = true) ||
                        it.contains("js-runtimes", ignoreCase = true) ||
                        it.contains("quickjs", ignoreCase = true) ||
                        it.contains("yt-dlp version", ignoreCase = true)
                }
                .take(12)
                .joinToString(" | ")
            logNote(
                "YtDlpDiag.verbose",
                if (interesting.isNotEmpty()) interesting else "no debug lines captured; raw head: ${text.take(400)}"
            )
        } catch (e: Exception) {
            logFailure("YtDlpDiag", e)
        }
    }

    fun getInitError(): Throwable? = initError

    fun retryInit(context: Context) {
        initError = null
        initialized = false
        updateChecked = false
        init(context)
    }

    fun notReadyReason(): String {
        val err = initError ?: return "yt-dlp not initialized yet, please wait..."
        val chain = generateSequence<Throwable>(err) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
        return "yt-dlp init failed: $chain"
    }

    fun isReady(): Boolean = initialized && updateChecked

    fun markUpdateChecked() { updateChecked = true }

    /** Full error goes to crash.log (long-press the language button to copy it). */
    private fun logFailure(tag: String, e: Throwable) {
        appContext?.let { ctx ->
            try {
                CrashLogger.logNonFatal(ctx, tag, e)
            } catch (_: Exception) { }
        }
    }

    private fun logNote(tag: String, message: String) {
        logFailure(tag, Exception(message))
    }

    /** Common options. No forced player_client, no custom user-agent, TLS checks on. */
    private fun baseRequest(url: String): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--socket-timeout", "30")
        request.addOption("--extractor-retries", "3")
        appContext?.let { ctx ->
            CookieHelper.getCookieFilePath(ctx)?.let { cookiePath ->
                request.addOption("--cookies", cookiePath)
            }
        }
        return request
    }

    /** Step 1: resolve a video URL to its info (thumbnail, title, duration). */
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            val request = baseRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-download")
            val response = YoutubeDL.getInstance().execute(request)
            val json = org.json.JSONObject(response.out)

            Result.success(
                VideoInfo(
                    url = url,
                    title = json.optString("title", ""),
                    uploader = json.optString("uploader", ""),
                    durationSec = json.optLong("duration", 0),
                    viewCount = formatViews(json.optLong("view_count", 0)),
                    thumbnailUrl = json.optString("thumbnail", "").ifEmpty { null }
                )
            )
        } catch (e: Exception) {
            logFailure("fetchVideoInfo", e)
            Result.failure(e)
        }
    }

    /**
     * Step 2: list downloadable formats, split into music and video.
     *
     * Client chain: tv first (token-free, downloadable formats), then the
     * default set (richest lists — up to 4K — but its URLs can be SABR-gated;
     * DownloadService's retry matrix covers that), then web_safari when the
     * best so far is below 1080p. Keeps whichever attempt is richest, so 4K
     * shows whenever the video has it.
     */
    suspend fun fetchFormats(url: String): Result<FormatsResult> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            ensureUpdated()
            // Order matters: try "tv" first — its formats are token-free and
            // actually DOWNLOAD (the default/web client's listed URLs are
            // SABR/PO-token gated and die with HTTP 403 in DownloadService,
            // field-proven build 246). Fall back to the default set (richest
            // lists, up to 4K), then web_safari if still below 1080p.
            val clients = listOf<String?>("tv", null, "web_safari")
            var best: FormatsResult? = null
            for ((index, client) in clients.withIndex()) {
                if (index == 2 && (best?.maxHeight ?: 0) >= 1080) break
                val attempt = try {
                    fetchFormatsOnce(url, client)
                } catch (e: Exception) {
                    logNote("fetchFormats.attempt", "client=${client ?: "default"} failed: ${e.message}")
                    null
                } ?: continue
                val current = best
                if (current == null ||
                    attempt.maxHeight > current.maxHeight ||
                    (attempt.maxHeight == current.maxHeight && attempt.video.size > current.video.size)
                ) {
                    best = attempt
                }
                if ((best?.maxHeight ?: 0) >= 2160) break
            }
            val winner = best ?: return@withContext Result.failure(Exception("No formats found"))
            Result.success(winner)
        } catch (e: Exception) {
            logFailure("fetchFormats", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchFormatsOnce(url: String, client: String?): FormatsResult =
        withContext(Dispatchers.IO) {
            val request = baseRequest(url)
            if (client != null) {
                request.addOption("--extractor-args", "youtube:player_client=$client")
            }
            request.addOption("--dump-json")
            request.addOption("--no-download")
            val response = YoutubeDL.getInstance().execute(request)
            val json = org.json.JSONObject(response.out)

            val music = mutableListOf<FormatOption>()
            val video = mutableListOf<FormatOption>()
            var maxHeight = 0
            val arr = json.optJSONArray("formats") ?: org.json.JSONArray()

            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.has("url")) continue

                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                // Skip storyboards / thumbnails (no video and no audio)
                if (vcodec == "none" && acodec == "none") continue

                val height = f.optInt("height", 0)
                val isAudioOnly = vcodec == "none" && acodec != "none"
                if (!isAudioOnly && height > maxHeight) maxHeight = height

                val label = if (isAudioOnly) {
                    val abr = f.optInt("abr", 0)
                    if (abr > 0) "${abr}K" else "Audio"
                } else {
                    if (height > 0) "${height}P" + if (height >= 720) " HD" else ""
                    else "Video"
                }

                val opt = FormatOption(
                    id = f.optString("format_id", ""),
                    label = label,
                    format = f.optString("ext", "").uppercase(),
                    sizeLabel = formatBytes(
                        f.optLong("filesize", 0).takeIf { it > 0 }
                            ?: f.optLong("filesize_approx", 0)
                    ),
                    isAudio = isAudioOnly
                )
                if (isAudioOnly) music.add(opt) else video.add(opt)
            }

            val sortedVideo = video.sortedBy {
                it.label.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
            }
            val errText = response.err ?: ""
            val poNote = when {
                errText.contains("PO Token", ignoreCase = true) -> " PO-TOKEN-warning"
                errText.contains("skipped", ignoreCase = true) -> " skipped-formats"
                else -> ""
            }
            // Diagnostic: per-attempt proof of what each client returned.
            logNote(
                "fetchFormats.list",
                "client=${client ?: "default"} url=$url maxHeight=$maxHeight " +
                    "video=${sortedVideo.size} [" +
                    sortedVideo.joinToString(", ") { "${it.label}/${it.format}/${it.id}" } +
                    "] audio=${music.size}$poNote"
            )
            FormatsResult(
                music = music,
                video = sortedVideo,
                maxHeight = maxHeight,
                clientUsed = client
            )
        }

    /**
     * Resolve ONE direct playable URL (video + audio together) for ExoPlayer.
     * "bv*+ba" would return two URLs and the player would get video only.
     */
    suspend fun resolveStreamUrl(url: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            ensureUpdated()
            val request = baseRequest(url)
            request.addOption("-g")
            request.addOption("-f", "b[ext=mp4]/b")
            val response = YoutubeDL.getInstance().execute(request)
            val streamUrl = response.out.lineSequence().firstOrNull()?.trim()
            Result.success(streamUrl?.ifEmpty { null })
        } catch (e: Exception) {
            logFailure("resolveStreamUrl", e)
            Result.failure(e)
        }
    }

    /** Update yt-dlp from the STABLE channel. Returns true if an update was installed. */
    private val updateMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var updateDone = false

    /**
     * Runs the yt-dlp self-update at most once per process, serialized.
     * Called from init (background) and awaited at the top of fetch/resolve
     * so the first extraction of a session already uses the latest yt-dlp.
     * A slow network never wedges a fetch: after 120s it proceeds with the
     * currently installed version and retries on the next call.
     */
    suspend fun ensureUpdated() {
        if (updateDone) return
        updateMutex.withLock {
            if (updateDone) return
            val r = kotlinx.coroutines.withTimeoutOrNull(120_000) { checkForUpdates() }
            if (r != null) {
                logNote(
                    "YtDlpManager.update",
                    "installed=${r.getOrNull()} error=${r.exceptionOrNull()?.message} " +
                        "version now: ${getYtDlpVersion()}"
                )
                updateDone = true
            } else {
                logNote("YtDlpManager.update", "update timed out (120s) — proceeding with installed version")
            }
        }
    }

    suspend fun checkForUpdates(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ctx = appContext ?: return@withContext Result.failure(Exception("Not initialized"))
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val status = YoutubeDL.getInstance()
                        .updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                    if (status == YoutubeDL.UpdateStatus.DONE ||
                        status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE
                    ) {
                        return@withContext Result.success(status == YoutubeDL.UpdateStatus.DONE)
                    }
                    lastError = Exception("Update status: $status, attempt $attempt")
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < 3) delay(2000)
                }
            }
            Result.failure(lastError ?: Exception("Update failed after 3 attempts"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Current yt-dlp version string for Settings -> About */
    fun getYtDlpVersion(): String {
        return try {
            val ctx = appContext ?: return "unknown"
            // versionName holds the release name set by the self-updater
            // (null until the first successful update).
            YoutubeDL.getInstance().versionName(ctx)
                ?: YoutubeDL.getInstance().version(ctx)
                ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun formatViews(count: Long): String = when {
        count >= 1_000_000 -> "${count / 1_000_000}M"
        count >= 1_000 -> "${count / 1_000}K"
        else -> count.toString()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_073_741_824 -> "%.1fGB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1fMB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    data class FormatsResult(
        val music: List<FormatOption>,
        val video: List<FormatOption>,
        val maxHeight: Int = 0,
        /** yt-dlp player_client that produced this list (null = library default). */
        val clientUsed: String? = null
    )
}
