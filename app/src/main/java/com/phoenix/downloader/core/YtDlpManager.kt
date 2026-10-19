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
import kotlinx.coroutines.withContext

/**
 * Wrapper around yt-dlp via the youtubedl-android library.
 * Call [init] once at app start before use.
 */
object YtDlpManager {

    private var initialized = false
    private var updateChecked = false
    private var appContext: Context? = null

    /** Set when init() throws; the real reason fetches fail. */
    private var initError: Throwable? = null

    fun init(context: Context) {
        if (initialized) return
        try {
            appContext = context.applicationContext
            YoutubeDL.getInstance().init(appContext!!)
            initialized = true
            updateChecked = true
            initError = null
            // Keep yt-dlp fresh: update in the background, never block the UI.
            CoroutineScope(Dispatchers.IO).launch { checkForUpdates() }
        } catch (e: Exception) {
            initError = e
            appContext?.let { ctx ->
                try {
                    CrashLogger.logNonFatal(ctx, "YtDlpManager.init", e)
                } catch (_: Exception) { }
            }
        }
    }

    fun isInitialized(): Boolean = initialized

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

    /**
     * Common options. No forced player_client, no custom user-agent and no
     * --no-check-certificate: yt-dlp's own defaults work best against YouTube.
     */
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
            appContext?.let { ctx ->
                try { CrashLogger.logNonFatal(ctx, "YtDlpManager.fetchVideoInfo", e) } catch (_: Exception) { }
            }
            Result.failure(e)
        }
    }

    /** Step 2: list downloadable formats, split into music and video. */
    suspend fun fetchFormats(url: String): Result<FormatsResult> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            val request = baseRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-download")
            val response = YoutubeDL.getInstance().execute(request)
            val json = org.json.JSONObject(response.out)

            val music = mutableListOf<FormatOption>()
            val video = mutableListOf<FormatOption>()
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
            Result.success(FormatsResult(music = music, video = sortedVideo))
        } catch (e: Exception) {
            appContext?.let { ctx ->
                try { CrashLogger.logNonFatal(ctx, "YtDlpManager.fetchFormats", e) } catch (_: Exception) { }
            }
            Result.failure(e)
        }
    }

    /**
     * Resolve ONE direct playable URL (video + audio together) for ExoPlayer.
     * "bv*+ba" would return two URLs and the player would get video only.
     */
    suspend fun resolveStreamUrl(url: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            val request = baseRequest(url)
            request.addOption("-g")
            request.addOption("-f", "b[ext=mp4]/b")
            val response = YoutubeDL.getInstance().execute(request)
            val streamUrl = response.out.lineSequence().firstOrNull()?.trim()
            Result.success(streamUrl?.ifEmpty { null })
        } catch (e: Exception) {
            appContext?.let { ctx ->
                try { CrashLogger.logNonFatal(ctx, "YtDlpManager.resolveStreamUrl", e) } catch (_: Exception) { }
            }
            Result.failure(e)
        }
    }

    /** Update yt-dlp from the STABLE channel. Returns true if an update was installed. */
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
            YoutubeDL.getInstance().version(ctx) ?: "unknown"
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
        val video: List<FormatOption>
    )
}
