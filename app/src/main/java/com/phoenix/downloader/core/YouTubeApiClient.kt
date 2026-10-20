package com.phoenix.downloader.core

import android.util.Log
import com.phoenix.downloader.ui.home.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Data API v3 client for trending videos.
 * Uses videos.list with chart=mostPopular (1 quota unit per call).
 */
object YouTubeApiClient {
    private const val TAG = "YouTubeApiClient"
    // API proxied via Cloudflare Worker (keeps key server-side, adds edge caching).
    // Worker handles regionCode quirks (e.g. YouTube rejects regionCode=MM).
    private const val WORKER_BASE = "https://phoenix-downloader-api.myokooo2004.workers.dev"
    // Legacy direct API (kept for reference; not used)
    private const val API_KEY = "AIzaSyDVpveH9BzSa7QZh7QT7Uh2zMasfEWR8Pw"
    private const val BASE_URL = "https://www.googleapis.com/youtube/v3/videos"

    /**
     * Fetch trending videos for a region.
     * @param regionCode ISO 3166-1 alpha-2 country code (e.g. "MM" for Myanmar, "US" for global)
     * @param maxResults 1-50
     * @param pageToken Token for pagination (null for first page)
     * @return Pair of (videos, nextPageToken)
     */
    suspend fun getTrending(regionCode: String = "MM", maxResults: Int = 20, pageToken: String? = null): Result<Pair<List<VideoItem>, String?>> = withContext(Dispatchers.IO) {
        try {
            var urlStr = "$WORKER_BASE/trending?region=$regionCode" +
                    "&maxResults=${maxResults.coerceIn(1, 50)}"
            if (pageToken != null) {
                urlStr += "&pageToken=$pageToken"
            }
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
                Log.e(TAG, "API error $code: $err")
                return@withContext Result.failure(Exception("YouTube API error: $code"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val nextPageToken = json.optString("nextPageToken", null)
            val items = json.getJSONArray("items")
            val videos = mutableListOf<VideoItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                // Handle both videos.list (id is string) and search.list (id.videoId) formats
                val id = try {
                    item.getString("id")
                } catch (e: Exception) {
                    try {
                        item.getJSONObject("id").getString("videoId")
                    } catch (e2: Exception) {
                        continue
                    }
                }
                val snippet = item.getJSONObject("snippet")
                val title = snippet.getString("title")
                val thumbnails = snippet.getJSONObject("thumbnails")
                // Prefer high/medium, fallback to default
                val thumbObj = when {
                    thumbnails.has("high") -> thumbnails.getJSONObject("high")
                    thumbnails.has("medium") -> thumbnails.getJSONObject("medium")
                    else -> thumbnails.getJSONObject("default")
                }
                val thumbUrl = thumbObj.getString("url")
                // contentDetails only in videos.list; search.list has no duration
                val durationSec = try {
                    val contentDetails = item.getJSONObject("contentDetails")
                    val durationIso = contentDetails.optString("duration", "")
                    parseIso8601Duration(durationIso)
                } catch (e: Exception) {
                    0
                }
                videos.add(
                    VideoItem(
                        url = "https://www.youtube.com/watch?v=$id",
                        title = title,
                        thumbnail = thumbUrl,
                        duration = durationSec
                    )
                )
            }
            Result.success(Pair(videos, nextPageToken))
        } catch (e: Exception) {
            Log.e(TAG, "getTrending failed", e)
            Result.failure(e)
        }
    }

    /** Parse ISO 8601 duration (e.g. PT4M13S) to seconds. */
    private fun parseIso8601Duration(iso: String): Int {
        if (iso.isEmpty()) return 0
        return try {
            var seconds = 0
            val timePart = iso.substringAfter("T", "")
            var num = ""
            for (c in timePart) {
                when {
                    c.isDigit() -> num += c
                    c == 'H' -> { seconds += num.toIntOrNull()?.times(3600) ?: 0; num = "" }
                    c == 'M' -> { seconds += num.toIntOrNull()?.times(60) ?: 0; num = "" }
                    c == 'S' -> { seconds += num.toIntOrNull() ?: 0; num = "" }
                }
            }
            seconds
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Fetch related videos for a video ID.
     * Uses search.list with relatedToVideoId (100 quota units per call).
     */
    suspend fun getRelatedVideos(videoId: String, maxResults: Int = 20, query: String? = null): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            var urlStr = "$WORKER_BASE/related" +
                    "?videoId=$videoId" +
                    "&maxResults=${maxResults.coerceIn(1, 50)}"
            if (query != null) {
                urlStr += "&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            }
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            if (code != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
                Log.e(TAG, "getRelatedVideos API error $code: $errorBody")
                return@withContext Result.failure(Exception("YouTube API error: $code - $errorBody"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val items = json.getJSONArray("items")
            val videos = mutableListOf<VideoItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val idObj = item.getJSONObject("id")
                val id = idObj.getString("videoId")
                val snippet = item.getJSONObject("snippet")
                val title = snippet.getString("title")
                val thumbnails = snippet.getJSONObject("thumbnails")
                val thumbObj = when {
                    thumbnails.has("high") -> thumbnails.getJSONObject("high")
                    thumbnails.has("medium") -> thumbnails.getJSONObject("medium")
                    else -> thumbnails.getJSONObject("default")
                }
                val thumbUrl = thumbObj.getString("url")
                videos.add(
                    VideoItem(
                        url = "https://www.youtube.com/watch?v=$id",
                        title = title,
                        thumbnail = thumbUrl,
                        duration = 0
                    )
                )
            }
            Result.success(videos)
        } catch (e: Exception) {
            Log.e(TAG, "getRelatedVideos failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch top-level comments for a video.
     * Uses commentThreads.list (1 quota unit per call).
     */
    suspend fun getComments(videoId: String, maxResults: Int = 20): Result<List<CommentItem>> = withContext(Dispatchers.IO) {
        try {
            val urlStr = "$WORKER_BASE/comments" +
                    "?videoId=$videoId" +
                    "&maxResults=${maxResults.coerceIn(1, 100)}"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            if (code != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
                Log.e(TAG, "getComments API error $code: $errorBody")
                return@withContext Result.failure(Exception("YouTube API error: $code - $errorBody"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val items = json.getJSONArray("items")
            val comments = mutableListOf<CommentItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val snippet = item.getJSONObject("snippet")
                val topComment = snippet.getJSONObject("topLevelComment")
                val commentSnippet = topComment.getJSONObject("snippet")
                val author = commentSnippet.getString("authorDisplayName")
                val text = commentSnippet.getString("textDisplay")
                val likeCount = commentSnippet.optInt("likeCount", 0)
                val authorAvatar = commentSnippet.optJSONObject("authorProfileImageUrl")?.optString("value", "")
                    ?: ""
                comments.add(CommentItem(author, text, likeCount, authorAvatar))
            }
            Result.success(comments)
        } catch (e: Exception) {
            Log.e(TAG, "getComments failed", e)
            Result.failure(e)
        }
    }

    /** Extract video ID from YouTube URL. */
    fun extractVideoId(url: String): String? {
        return try {
            when {
                url.contains("youtube.com/watch") -> {
                    url.substringAfter("v=").substringBefore("&")
                }
                url.contains("youtu.be/") -> {
                    url.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Data class for YouTube comments. */
data class CommentItem(
    val author: String,
    val text: String,
    val likeCount: Int,
    val avatarUrl: String
)
