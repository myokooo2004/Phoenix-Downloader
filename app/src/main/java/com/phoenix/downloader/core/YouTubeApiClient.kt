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
    private const val API_KEY = "AIzaSyDVpveH9BzSa7QZh7QT7Uh2zMasfEWR8Pw"
    private const val BASE_URL = "https://www.googleapis.com/youtube/v3/videos"

    /**
     * Fetch trending videos for a region.
     * @param regionCode ISO 3166-1 alpha-2 country code (e.g. "MM" for Myanmar, "US" for global)
     * @param maxResults 1-50
     */
    suspend fun getTrending(regionCode: String = "MM", maxResults: Int = 20): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val urlStr = "$BASE_URL?part=snippet,contentDetails,statistics" +
                    "&chart=mostPopular" +
                    "&regionCode=$regionCode" +
                    "&maxResults=${maxResults.coerceIn(1, 50)}" +
                    "&key=$API_KEY"
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
            val items = json.getJSONArray("items")
            val videos = mutableListOf<VideoItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val id = item.getString("id")
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
                val contentDetails = item.getJSONObject("contentDetails")
                val durationIso = contentDetails.optString("duration", "")
                val durationSec = parseIso8601Duration(durationIso)
                videos.add(
                    VideoItem(
                        url = "https://www.youtube.com/watch?v=$id",
                        title = title,
                        thumbnail = thumbUrl,
                        duration = durationSec
                    )
                )
            }
            Result.success(videos)
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
}
