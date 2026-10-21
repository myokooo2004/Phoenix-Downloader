package com.phoenix.downloader.core

import android.util.Log
import com.phoenix.downloader.ui.home.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * YouTube Data API v3 client for trending videos.
 * Uses videos.list with chart=mostPopular (1 quota unit per call).
 */
object YouTubeApiClient {
    private const val TAG = "YouTubeApiClient"
    // API proxied via Cloudflare Worker (keeps key server-side, adds edge caching).
    // Worker handles regionCode quirks (e.g. YouTube rejects regionCode=MM).
    private const val WORKER_BASE = "https://api.phoenixdl.dpdns.org"
    // Legacy direct API (kept for reference; not used)
    private const val API_KEY = "AIzaSyDVpveH9BzSa7QZh7QT7Uh2zMasfEWR8Pw"
    private const val BASE_URL = "https://www.googleapis.com/youtube/v3/videos"

    // --- Worker transport --------------------------------------------------
    // Field evidence (2026-10-21): on the user's MPT line the system DNS
    // returns a poisoned/restricted answer for the Worker host (a single
    // connection-blocked Cloudflare IP), so every Worker call is reset
    // unless a VPN is on. Chrome reaches the host because its Secure DNS
    // gets the full honest record set. Do the same here: resolve the
    // Worker host via DNS-over-HTTPS on port 443 (Cloudflare), with the
    // resolver's own IPs as bootstrap so no system DNS is needed for it.
    // If DoH itself fails, fall back to the system resolver — the app is
    // then exactly as reachable as before, never worse.
    private val dohDns: Dns = DnsOverHttps.Builder()
        .client(OkHttpClient())
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
            InetAddress.getByName("2606:4700:4700::1111"),
            InetAddress.getByName("2606:4700:4700::1001")
        )
        .build()

    private val workerDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            try {
                dohDns.lookup(hostname)
            } catch (e: Exception) {
                Log.w(TAG, "DoH lookup failed for $hostname; system DNS fallback", e)
                Dns.SYSTEM.lookup(hostname)
            }
    }

    private val workerClient: OkHttpClient = OkHttpClient.Builder()
        .dns(workerDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * GET [urlStr] through the DoH-backed client. Returns (status, body);
     * on a non-200 the body is the error body, matching what callers used
     * to read from HttpURLConnection.errorStream. Transport failures throw
     * (callers' existing try/catch and IOException retry logic rely on it).
     */
    private fun workerGet(urlStr: String, timeoutMs: Long = 15000L): Pair<Int, String> {
        val client = workerClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(urlStr).get().build()
        client.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

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
            val (code, body) = workerGet(urlStr)
            if (code != 200) {
                Log.e(TAG, "API error $code: $body")
                return@withContext Result.failure(Exception("YouTube API error: $code"))
            }

            val json = JSONObject(body)
            val nextPageToken = json.optString("nextPageToken", null)
            val items = json.getJSONArray("items")
            val videos = mutableListOf<VideoItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                // Handle both videos.list (id is a plain string) and search.list
                // (id is an object {kind, videoId}). NOTE: JSONObject.getString()
                // on an object returns its JSON text instead of throwing,
                // so we MUST check optJSONObject first.
                val idObj = item.optJSONObject("id")
                val id = if (idObj != null) {
                    idObj.optString("videoId", "")
                } else {
                    item.optString("id", "")
                }
                if (id.isEmpty()) continue
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

    /** One page of related videos plus the token for the next page (null = end). */
    data class RelatedPage(val videos: List<VideoItem>, val nextPageToken: String?)

    /**
     * Search YouTube videos via the Worker (/search -> search.list).
     * Returns the page's videos plus the next page token (null = no more).
     */
    suspend fun searchVideos(
        query: String,
        maxResults: Int = 20,
        pageToken: String? = null
    ): Result<Pair<List<VideoItem>, String?>> = withContext(Dispatchers.IO) {
        try {
            var urlStr = "$WORKER_BASE/search" +
                    "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                    "&maxResults=${maxResults.coerceIn(1, 50)}"
            if (!pageToken.isNullOrEmpty()) {
                urlStr += "&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}"
            }
            val (code, body) = workerGet(urlStr)
            if (code != 200) {
                Log.e(TAG, "searchVideos API error $code: $body")
                return@withContext Result.failure(Exception("YouTube API error: $code - $body"))
            }

            val json = JSONObject(body)
            val nextToken = json.optString("nextPageToken", "").ifEmpty { null }
            val items = json.optJSONArray("items") ?: org.json.JSONArray()
            val videos = mutableListOf<VideoItem>()
            for (i in 0 until items.length()) {
                try {
                    val item = items.getJSONObject(i)
                    val idObj = item.optJSONObject("id") ?: continue
                    val id = idObj.optString("videoId", "")
                    if (id.isEmpty()) continue
                    val snippet = item.getJSONObject("snippet")
                    val title = snippet.optString("title", "")
                    val thumbnails = snippet.getJSONObject("thumbnails")
                    val thumbObj = when {
                        thumbnails.has("high") -> thumbnails.getJSONObject("high")
                        thumbnails.has("medium") -> thumbnails.getJSONObject("medium")
                        else -> thumbnails.getJSONObject("default")
                    }
                    videos.add(
                        VideoItem(
                            url = "https://www.youtube.com/watch?v=$id",
                            title = title,
                            thumbnail = thumbObj.getString("url"),
                            duration = 0
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "searchVideos: skipping bad item $i", e)
                    continue
                }
            }
            Result.success(videos to nextToken)
        } catch (e: Exception) {
            Log.e(TAG, "searchVideos failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch one page of related videos (Worker /related -> search.list by
     * title; relatedToVideoId is deprecated). Pass [pageToken] from the
     * previous page for infinite scrolling.
     */
    suspend fun getRelatedVideos(
        videoId: String,
        maxResults: Int = 20,
        query: String? = null,
        pageToken: String? = null
    ): Result<RelatedPage> {
        // One retry on a transient network failure: a single connection
        // reset on weak mobile data used to leave Related permanently empty
        // for that screen (field log 2026-10-20).
        val first = getRelatedVideosOnce(videoId, maxResults, query, pageToken)
        if (first.isSuccess) return first
        if (first.exceptionOrNull() !is java.io.IOException) return first
        delay(1200)
        return getRelatedVideosOnce(videoId, maxResults, query, pageToken)
    }

    private suspend fun getRelatedVideosOnce(
        videoId: String,
        maxResults: Int = 20,
        query: String? = null,
        pageToken: String? = null
    ): Result<RelatedPage> = withContext(Dispatchers.IO) {
        try {
            var urlStr = "$WORKER_BASE/related" +
                    "?videoId=$videoId" +
                    "&maxResults=${maxResults.coerceIn(1, 50)}"
            if (query != null) {
                urlStr += "&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            }
            if (!pageToken.isNullOrEmpty()) {
                urlStr += "&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}"
            }
            val (code, body) = workerGet(urlStr)
            if (code != 200) {
                Log.e(TAG, "getRelatedVideos API error $code: $body")
                return@withContext Result.failure(Exception("YouTube API error: $code - $body"))
            }

            val json = JSONObject(body)
            val nextToken = json.optString("nextPageToken", "").ifEmpty { null }
            val items = json.getJSONArray("items")
            val videos = mutableListOf<VideoItem>()
            for (i in 0 until items.length()) {
                // Defensive per-item parse: one malformed item (e.g. a channel
                // result without videoId) must not kill the whole list.
                try {
                    val item = items.getJSONObject(i)
                    val idObj = item.optJSONObject("id") ?: continue
                    val id = idObj.optString("videoId", "")
                    if (id.isEmpty()) continue
                    val snippet = item.getJSONObject("snippet")
                    val title = snippet.optString("title", "")
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
                } catch (e: Exception) {
                    Log.w(TAG, "getRelatedVideos: skipping bad item $i", e)
                    continue
                }
            }
            Result.success(RelatedPage(videos, nextToken))
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
            val (code, body) = workerGet(urlStr)
            if (code != 200) {
                Log.e(TAG, "getComments API error $code: $body")
                return@withContext Result.failure(Exception("YouTube API error: $code - $body"))
            }

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
    /**
     * Live search suggestions while typing (YouTube's public suggest
     * endpoint — no API key, no quota). Returns an empty list on any
     * failure; the search screen just keeps showing the sections then.
     */
    suspend fun fetchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$enc")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            if (conn.responseCode != 200) return@withContext emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            val list = arr.optJSONArray(1) ?: return@withContext emptyList()
            (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Trending mirror hosted on this repo's `data` branch (refreshed
     * hourly by CI from the Worker's /trending.json). raw.githubuser-
     * content.com is reachable on networks where the Worker's own hosts
     * are blocked, so Home fills and keeps scrolling without a VPN
     * (user field report 2026-10-21). Empty on failure — Home falls
     * back to the Worker path.
     */
    suspend fun fetchTrendingFeed(): List<VideoItem> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://raw.githubusercontent.com/myokooo2004/Phoenix-Downloader/data/trending.json")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode != 200) return@withContext emptyList()
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("videos")
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url")
                if (u.isBlank()) return@mapNotNull null
                VideoItem(u, o.optString("title"), o.optString("thumbnailUrl"), o.optInt("duration"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Hot Searches composed by the Worker from YouTube's own suggestion
     * rankings (no hand-picked terms anywhere). Empty on failure — the
     * search screen then keeps its cached list, or hides the section.
     */
    suspend fun fetchHotSearches(): List<String> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = workerGet("$WORKER_BASE/hot", 8000L)
            if (code != 200) return@withContext emptyList()
            val arr = JSONObject(body).optJSONArray("terms")
                ?: return@withContext emptyList()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

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
