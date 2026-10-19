package com.phoenix.downloader.core

import android.content.Context
import com.phoenix.downloader.model.FormatOption
import com.phoenix.downloader.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper around yt-dlp via the youtubedl-android library.
 *
 * This is the same library used by Seal and other top Android
 * downloader apps. It bundles the yt-dlp binary + Python runtime,
 * with a built-in updater (STABLE/NIGHTLY/MASTER channels).
 *
 * Call [init] once from Application.onCreate() before use.
 */
object YtDlpManager {

    private var initialized = false
    private var updateChecked = false
    private var appContext: Context? = null
    /** Set when init() throws — the real reason fetches fail (never silent). */
    private var initError: Throwable? = null

    /** Must be called once from Application.onCreate(). */
    fun init(context: Context) {
        if (!initialized) {
            try {
                appContext = context.applicationContext
                YoutubeDL.getInstance().init(appContext!!)
                initialized = true
                // CI bundles latest yt-dlp at build time, so we're ready immediately.
                // In-app updater runs in background without blocking.
                updateChecked = true
                initError = null
            } catch (e: Exception) {
                initError = e
                // Log full stack trace for diagnostics (user can copy via long-press)
                appContext?.let { ctx ->
                    try {
                        CrashLogger.logNonFatal(ctx, "YtDlpManager.init", e)
                    } catch (_: Exception) { }
                }
            }
        }
    }

    fun isInitialized(): Boolean = initialized

    /** The exception from init(), or null if init succeeded (or never ran). */
    fun getInitError(): Throwable? = initError

    /** Retry init() after a failure (e.g. from a "tap to retry" UI). */
    fun retryInit(context: Context) {
        initError = null
        initialized = false
        updateChecked = false
        init(context)
    }

    /** Human-readable reason fetches are failing — for error UI, not logcat-only. */
    fun notReadyReason(): String =
        initError?.let { "yt-dlp init failed: ${it.message}" }
            ?: "yt-dlp not initialized yet, please wait..."
    
    /** True when init AND update check both completed - safe to use for downloads */
    fun isReady(): Boolean = initialized && updateChecked
    
    fun markUpdateChecked() { updateChecked = true }

    /** Step 1: resolve a video URL to its info (thumbnail, title, duration). */
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-download")
            request.addOption("--no-check-certificate")
            // Android client bypasses YouTube bot detection without login (Vidmate-style)
            request.addOption("--extractor-args")
            request.addOption("youtube:player_client=android")
            request.addOption("--user-agent")
            request.addOption("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            request.addOption("--socket-timeout")
            request.addOption("30")
            // Use browser cookies to bypass bot detection (from Python file insight)
            appContext?.let { ctx ->
                CookieHelper.getCookieFilePath(ctx)?.let { cookiePath ->
                    request.addOption("--cookies")
                    request.addOption(cookiePath)
                }
            }
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
            Result.failure(e)
        }
    }

    /**
     * Step 2: list downloadable formats, split into music (audio-only)
     * and video options for the quality grid.
     */
    suspend fun fetchFormats(url: String): Result<FormatsResult> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-download")
            request.addOption("--no-check-certificate")
            // Android client bypasses YouTube bot detection without login (Vidmate-style)
            request.addOption("--extractor-args")
            request.addOption("youtube:player_client=android")
            request.addOption("--user-agent")
            request.addOption("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            request.addOption("--extractor-retries")
            request.addOption("3")
            request.addOption("--socket-timeout")
            request.addOption("30")
            // Use browser cookies to bypass bot detection
            appContext?.let { ctx ->
                CookieHelper.getCookieFilePath(ctx)?.let { cookiePath ->
                    request.addOption("--cookies")
                    request.addOption(cookiePath)
                }
            }
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
                    sizeLabel = formatBytes(f.optLong("filesize", 0).takeIf { it > 0 }
                        ?: f.optLong("filesize_approx", 0)),
                    isAudio = isAudioOnly
                )
                if (isAudioOnly) music.add(opt) else video.add(opt)
            }

            val sortedVideo = video.sortedBy {
                it.label.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
            }
            Result.success(FormatsResult(music = music, video = sortedVideo))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolve a direct playable stream URL for in-app playback (ExoPlayer).
     */
    suspend fun resolveStreamUrl(url: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            val request = YoutubeDLRequest(url)
            request.addOption("-g")
            request.addOption("-f")
            request.addOption("bv*+ba/b")
            request.addOption("--no-check-certificate")
            // Android client bypasses YouTube bot detection without login (Vidmate-style)
            request.addOption("--extractor-args")
            request.addOption("youtube:player_client=android")
            request.addOption("--user-agent")
            request.addOption("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            request.addOption("--socket-timeout")
            request.addOption("30")
            // Use browser cookies to bypass bot detection
            appContext?.let { ctx ->
                CookieHelper.getCookieFilePath(ctx)?.let { cookiePath ->
                    request.addOption("--cookies")
                    request.addOption(cookiePath)
                }
            }
            val response = YoutubeDL.getInstance().execute(request)
            val streamUrl = response.out.lineSequence().firstOrNull()?.trim()
            Result.success(streamUrl?.ifEmpty { null })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check for yt-dlp updates. Call on app start (background thread).
     * Returns true if an update was installed.
     */
    suspend fun checkForUpdates(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ctx = appContext ?: return@withContext Result.failure(Exception("Not initialized"))
            // Retry up to 3 times - mobile networks are flaky, library has short timeouts
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val status = YoutubeDL.getInstance()
                        .updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                    if (status == YoutubeDL.UpdateStatus.DONE || 
                        status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                        return@withContext Result.success(status == YoutubeDL.UpdateStatus.DONE)
                    }
                    // If update failed but not exception, retry
                    lastError = Exception("Update status: $status, attempt $attempt")
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < 3) {
                        kotlinx.coroutines.delay(2000) // Wait 2s before retry
                    }
                }
            }
            Result.failure(lastError ?: Exception("Update failed after 3 attempts"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Get current yt-dlp version string for Settings -> About */
    fun getYtDlpVersion(): String {
        return try {
            val ctx = appContext ?: return "unknown"
            YoutubeDL.getInstance().version(ctx) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Fetch trending videos from GitHub (pre-fetched by daily workflow).
     * Fast - just a simple HTTP GET, no on-device yt-dlp needed.
     * Falls back to on-device yt-dlp if the fetch fails.
     */
    suspend fun fetchTrending(): Result<List<VideoInfo>> = withContext(Dispatchers.IO) {
        try {
            // Try GitHub-hosted trending.json first (fast)
            val url = java.net.URL("https://raw.githubusercontent.com/myokooo2004/Phoenix-Downloader/main/trending.json")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val jsonText = connection.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(jsonText)
                val videosArray = json.optJSONArray("videos") ?: org.json.JSONArray()

                val videos = mutableListOf<VideoInfo>()
                for (i in 0 until videosArray.length()) {
                    val item = videosArray.getJSONObject(i)
                    videos.add(
                        VideoInfo(
                            url = item.optString("url", ""),
                            title = item.optString("title", ""),
                            uploader = item.optString("uploader", ""),
                            durationSec = item.optLong("duration", 0),
                            viewCount = formatViews(item.optLong("viewCount", 0)),
                            thumbnailUrl = item.optString("thumbnailUrl", "").ifEmpty { null }
                        )
                    )
                }

                if (videos.isNotEmpty()) {
                    return@withContext Result.success(videos)
                }
            }
        } catch (e: Exception) {
            // Fall through to on-device fetch
        }

        // Fallback: on-device yt-dlp (slow, but works offline from GitHub)
        try {
            if (!initialized) return@withContext Result.failure(Exception("yt-dlp not initialized yet, please wait"))
            val request = YoutubeDLRequest("https://www.youtube.com/feed/trending")
            request.addOption("--flat-playlist")
            request.addOption("--dump-json")
            request.addOption("--no-download")
            val response = YoutubeDL.getInstance().execute(request)

            val videos = mutableListOf<VideoInfo>()
            // --flat-playlist outputs one JSON object per line
            response.out.lines().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val json = org.json.JSONObject(line)
                    val videoId = json.optString("id", "")
                    if (videoId.isNotEmpty()) {
                        videos.add(
                            VideoInfo(
                                url = "https://www.youtube.com/watch?v=$videoId",
                                title = json.optString("title", ""),
                                uploader = json.optString("uploader", ""),
                                durationSec = json.optLong("duration", 0),
                                viewCount = formatViews(json.optLong("view_count", 0)),
                                thumbnailUrl = json.optJSONArray("thumbnails")?.let { arr ->
                                    if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optString("url", "").ifEmpty { null }
                                    else null
                                }
                            )
                        )
                    }
                    if (videos.size >= 20) return@forEach // Limit to 20
                } catch (e: Exception) {
                    // Skip malformed lines
                }
            }
            Result.success(videos)
        } catch (e: Exception) {
            Result.failure(e)
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
