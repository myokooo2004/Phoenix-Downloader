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
    private var appContext: Context? = null

    /** Must be called once from Application.onCreate(). */
    fun init(context: Context) {
        if (!initialized) {
            appContext = context.applicationContext
            YoutubeDL.getInstance().init(appContext!!)
            initialized = true
        }
    }

    fun isInitialized(): Boolean = initialized

    /** Step 1: resolve a video URL to its info (thumbnail, title, duration). */
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            if (!initialized) return@withContext Result.failure(Exception("yt-dlp not initialized yet, please wait"))
            val request = YoutubeDLRequest(url)
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
            Result.failure(e)
        }
    }

    /**
     * Step 2: list downloadable formats, split into music (audio-only)
     * and video options for the quality grid.
     */
    suspend fun fetchFormats(url: String): Result<FormatsResult> = withContext(Dispatchers.IO) {
        try {
            if (!initialized) return@withContext Result.failure(Exception("yt-dlp not initialized yet, please wait"))
            val request = YoutubeDLRequest(url)
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
            if (!initialized) return@withContext Result.failure(Exception("yt-dlp not initialized yet, please wait"))
            val request = YoutubeDLRequest(url)
            request.addOption("-g")
            request.addOption("-f")
            request.addOption("bv*+ba/b")
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
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            Result.success(status == YoutubeDL.UpdateStatus.DONE)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch YouTube trending videos for the home feed.
     * Uses --flat-playlist to get the list without downloading.
     */
    suspend fun fetchTrending(): Result<List<VideoInfo>> = withContext(Dispatchers.IO) {
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
