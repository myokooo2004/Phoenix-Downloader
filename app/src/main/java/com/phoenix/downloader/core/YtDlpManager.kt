package com.phoenix.downloader.core

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.phoenix.downloader.model.FormatOption
import com.phoenix.downloader.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Wrapper around yt-dlp via Chaquopy (Python for Android).
 *
 * Calls into app/src/main/python/ytdlp_bridge.py for:
 * - Video info (title, thumbnail, duration)
 * - Available formats (music + video quality grid)
 * - Direct stream URL resolution (for ExoPlayer playback)
 */
object YtDlpManager {

    private var initialized = false

    /** Must be called once from Application.onCreate(). */
    fun init(context: Context) {
        if (!initialized) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            initialized = true
        }
    }

    private fun bridge() = Python.getInstance().getModule("ytdlp_bridge")

    /** Step 1: resolve a video URL to its info (thumbnail, title, duration). */
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val json = bridge().callAttr("get_video_info", url).toString()
            val o = JSONObject(json)
            if (!o.getBoolean("ok")) {
                return@withContext Result.failure(Exception(o.optString("error", "Unknown error")))
            }
            Result.success(
                VideoInfo(
                    url = url,
                    title = o.optString("title", ""),
                    uploader = o.optString("uploader", ""),
                    durationSec = o.optInt("duration", 0),
                    viewCount = formatViews(o.optLong("view_count", 0)),
                    thumbnailUrl = o.optString("thumbnail", "").ifEmpty { null }
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
            val json = bridge().callAttr("get_formats", url).toString()
            val o = JSONObject(json)
            if (!o.getBoolean("ok")) {
                return@withContext Result.failure(Exception(o.optString("error", "Unknown error")))
            }
            val music = mutableListOf<FormatOption>()
            val video = mutableListOf<FormatOption>()
            val arr = o.getJSONArray("formats")
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                val height = f.optInt("height", 0)
                val isAudioOnly = vcodec == "none" && acodec != "none"

                val label = if (isAudioOnly) {
                    val abr = f.optInt("abr", 0)
                    if (abr > 0) "${abr}K" else f.optString("resolution", "Audio")
                } else {
                    if (height > 0) "${height}P" + if (height >= 720) " HD" else ""
                    else f.optString("resolution", "Video")
                }

                val opt = FormatOption(
                    id = f.optString("format_id", ""),
                    label = label,
                    format = f.optString("ext", "").uppercase(),
                    sizeLabel = formatBytes(f.optLong("filesize", 0)),
                    isAudio = isAudioOnly
                )
                if (isAudioOnly) music.add(opt) else video.add(opt)
            }
            // Sort video by height ascending (144P -> 4K)
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
            val json = bridge().callAttr("get_stream_url", url, "bv*+ba/b").toString()
            val o = JSONObject(json)
            if (!o.getBoolean("ok")) {
                return@withContext Result.failure(Exception(o.optString("error", "Unknown error")))
            }
            Result.success(o.optString("url", "").ifEmpty { null })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Get the bundled yt-dlp version. */
    suspend fun getYtDlpVersion(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = bridge().callAttr("get_version").toString()
            val o = JSONObject(json)
            if (o.getBoolean("ok")) Result.success(o.getString("version"))
            else Result.failure(Exception(o.optString("error")))
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
