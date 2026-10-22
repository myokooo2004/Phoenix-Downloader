package com.phoenix.downloader.core

import android.util.Log
import com.phoenix.downloader.ui.home.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube discovery straight from the phone (user direction 2026-10-22:
 * the native UI stays, but the data behind it must come the way the
 * browser gets it — directly from YouTube, not through our Worker).
 *
 * The browser is fast because youtube.com's own client talks to
 * YouTube from the phone's connection; the Worker path added a
 * Cloudflare hop whose IPs are connection-killed on some networks,
 * plus a relay detour on top. These calls are the same anonymous
 * InnerTube requests the web client makes (public web key embedded in
 * every youtube.com page; no Data API quota), so Home / Search /
 * Related / Comments behave exactly like YouTube's own lists — no
 * filtering, dedupe or reordering on our side beyond reshaping items
 * into the app's VideoItem model.
 *
 * Every function returns null on any failure or shape drift; callers
 * fall back to the Worker path, so a bad day here can never do worse
 * than the old behaviour.
 */
object DirectYouTube {
    private const val TAG = "DirectYouTube"

    // The public InnerTube web key (embedded in every youtube.com page;
    // the same key the Worker uses). NOT the Data API key — no quota.
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val CLIENT_VERSION = "2.20240111.09.00"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun baseBody(): JSONObject = JSONObject().put(
        "context",
        JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", "US")
        )
    )

    private suspend fun post(endpoint: String, body: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/$endpoint?key=$API_KEY&prettyPrint=false")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", UA)
                    .header("X-YouTube-Client-Name", "1")
                    .header("X-YouTube-Client-Version", CLIENT_VERSION)
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "$endpoint HTTP ${resp.code}")
                        return@withContext null
                    }
                    val text = resp.body?.string() ?: return@withContext null
                    JSONObject(text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "$endpoint failed", e)
                null
            }
        }

    // ---- small shape helpers -------------------------------------------

    /** runs[] joined, else simpleText, else a plain string field. */
    private fun textOf(node: JSONObject?): String {
        if (node == null) return ""
        val runs = node.optJSONArray("runs")
        if (runs != null) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
            }
            val joined = sb.toString().trim()
            if (joined.isNotEmpty()) return joined
        }
        return node.optString("simpleText", "").trim()
    }

    private fun badgeToSeconds(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var acc = 0
        for (part in text.split(":")) {
            val n = part.trim().toIntOrNull() ?: return 0
            acc = acc * 60 + n
        }
        return acc
    }

    /** "1.2K" -> 1200, "15K" -> 15000, "3" -> 3, "" -> 0. */
    private fun parseCount(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val t = text.trim().replace(",", "")
        return try {
            when {
                t.endsWith("K", true) -> (t.dropLast(1).toDouble() * 1_000).toInt()
                t.endsWith("M", true) -> (t.dropLast(1).toDouble() * 1_000_000).toInt()
                t.endsWith("B", true) -> (t.dropLast(1).toDouble() * 1_000_000_000).toInt()
                else -> t.toDouble().toInt()
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun largestThumbUrl(thumbs: JSONArray?): String {
        if (thumbs == null) return ""
        var best = ""
        var bestW = -1
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val url = t.optString("url", "")
            if (url.isEmpty()) continue
            val w = t.optInt("width", 0)
            if (w >= bestW) {
                bestW = w
                best = url
            }
        }
        return best
    }

    private fun continuationTokenOf(entry: JSONObject): String? {
        val cir = entry.optJSONObject("continuationItemRenderer") ?: return null
        val token = cir.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")
            ?.optString("token", "")
        return token?.ifEmpty { null }
    }

    // ---- item parsers (ported from the Worker's field-proven shapes) --

    private fun lockupToItem(lk: JSONObject): VideoItem? {
        return try {
            val videoId = lk.optString("contentId", "")
            if (videoId.isEmpty()) return null
            if (lk.optString("contentType", "") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
            val md = lk.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                ?: return null
            val title = md.optJSONObject("title")?.optString("content", "") ?: ""
            if (title.isEmpty()) return null
            var durationSec = 0
            val overlays = lk.optJSONObject("contentImage")
                ?.optJSONObject("thumbnailViewModel")
                ?.optJSONArray("overlays")
            if (overlays != null) {
                for (i in 0 until overlays.length()) {
                    val badges = overlays.optJSONObject(i)
                        ?.optJSONObject("thumbnailBottomOverlayViewModel")
                        ?.optJSONArray("badges") ?: continue
                    for (j in 0 until badges.length()) {
                        val t = badges.optJSONObject(j)
                            ?.optJSONObject("thumbnailBadgeViewModel")
                            ?.optString("text", "")
                        if (!t.isNullOrEmpty()) durationSec = badgeToSeconds(t)
                    }
                }
            }
            val sources = lk.optJSONObject("contentImage")
                ?.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")
                ?.optJSONArray("sources")
            val thumb = largestThumbUrl(sources)
                .ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
            VideoItem("https://www.youtube.com/watch?v=$videoId", title, thumb, durationSec)
        } catch (e: Exception) {
            null
        }
    }

    private fun videoRendererToItem(vr: JSONObject): VideoItem? {
        return try {
            val videoId = vr.optString("videoId", "")
            if (videoId.isEmpty()) return null
            val title = textOf(vr.optJSONObject("title"))
            if (title.isEmpty()) return null
            val thumb = largestThumbUrl(vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                .ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
            val durationSec = badgeToSeconds(
                vr.optJSONObject("lengthText")?.optString("simpleText", "")
            )
            VideoItem("https://www.youtube.com/watch?v=$videoId", title, thumb, durationSec)
        } catch (e: Exception) {
            null
        }
    }

    // ---- collectors -----------------------------------------------------

    /** Up-next arrays: lockups (+ classic renderers), in order, + token. */
    private fun collectNextItems(arr: JSONArray?): Pair<List<VideoItem>, String?> {
        val items = mutableListOf<VideoItem>()
        var token: String? = null
        if (arr == null) return items to token
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            when {
                entry.has("lockupViewModel") ->
                    lockupToItem(entry.getJSONObject("lockupViewModel"))?.let { items.add(it) }
                entry.has("videoRenderer") ->
                    videoRendererToItem(entry.getJSONObject("videoRenderer"))?.let { items.add(it) }
                entry.has("continuationItemRenderer") ->
                    continuationTokenOf(entry)?.let { token = it }
            }
        }
        return items to token
    }

    /** Search arrays: item sections recurse; videos collect in order. */
    private fun collectSearchItems(entries: JSONArray?): Pair<List<VideoItem>, String?> {
        val items = mutableListOf<VideoItem>()
        var token: String? = null
        if (entries == null) return items to token
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            when {
                entry.has("itemSectionRenderer") -> {
                    val sub = collectSearchItems(
                        entry.getJSONObject("itemSectionRenderer").optJSONArray("contents")
                    )
                    items.addAll(sub.first)
                    if (sub.second != null) token = sub.second
                }
                entry.has("videoRenderer") ->
                    videoRendererToItem(entry.getJSONObject("videoRenderer"))?.let { items.add(it) }
                entry.has("lockupViewModel") ->
                    lockupToItem(entry.getJSONObject("lockupViewModel"))?.let { items.add(it) }
                entry.has("continuationItemRenderer") ->
                    continuationTokenOf(entry)?.let { token = it }
            }
        }
        return items to token
    }

    /** Home grid arrays: richItemRenderer wraps the video entry. */
    private fun collectBrowseItems(entries: JSONArray?): Pair<List<VideoItem>, String?> {
        val items = mutableListOf<VideoItem>()
        var token: String? = null
        if (entries == null) return items to token
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            when {
                entry.has("richItemRenderer") -> {
                    val content = entry.getJSONObject("richItemRenderer")
                        .optJSONObject("content") ?: continue
                    when {
                        content.has("videoRenderer") ->
                            videoRendererToItem(content.getJSONObject("videoRenderer"))
                                ?.let { items.add(it) }
                        content.has("lockupViewModel") ->
                            lockupToItem(content.getJSONObject("lockupViewModel"))
                                ?.let { items.add(it) }
                    }
                }
                entry.has("videoRenderer") ->
                    videoRendererToItem(entry.getJSONObject("videoRenderer"))?.let { items.add(it) }
                entry.has("lockupViewModel") ->
                    lockupToItem(entry.getJSONObject("lockupViewModel"))?.let { items.add(it) }
                entry.has("continuationItemRenderer") ->
                    continuationTokenOf(entry)?.let { token = it }
            }
        }
        return items to token
    }

    /** First append/reload continuation items array in a response root. */
    private fun firstContinuationItems(root: JSONObject): JSONArray? {
        for (key in listOf(
            "onResponseReceivedEndpoints",
            "onResponseReceivedActions",
            "onResponseReceivedCommands"
        )) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val ep = arr.optJSONObject(i) ?: continue
                ep.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems")
                    ?.let { return it }
                ep.optJSONObject("reloadContinuationItemsCommand")
                    ?.optJSONArray("continuationItems")
                    ?.let { return it }
            }
        }
        return null
    }

    /** First nested object stored under [key], depth-first. */
    private fun findObject(node: Any?, key: String, depth: Int = 0): JSONObject? {
        if (node == null || depth > 40) return null
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                val names = node.names() ?: return null
                for (i in 0 until names.length()) {
                    findObject(node.opt(names.getString(i)), key, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findObject(node.opt(i), key, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    // ---- public API -----------------------------------------------------

    /** YouTube's own home feed (what youtube.com shows signed-out). */
    suspend fun homeFeed(continuation: String?): Pair<List<VideoItem>, String?>? {
        val body = baseBody()
        if (continuation.isNullOrEmpty()) body.put("browseId", "FEwhat_to_watch")
        else body.put("continuation", continuation)
        val root = post("browse", body) ?: return null
        return try {
            if (continuation.isNullOrEmpty()) {
                val grid = findObject(root, "richGridRenderer") ?: return null
                val collected = collectBrowseItems(grid.optJSONArray("contents"))
                if (collected.first.isEmpty()) null else collected
            } else {
                val items = firstContinuationItems(root) ?: return null
                collectBrowseItems(items)
            }
        } catch (e: Exception) {
            Log.w(TAG, "homeFeed parse failed", e)
            null
        }
    }

    /** YouTube's own search results for [query], in YouTube's order. */
    suspend fun search(
        query: String,
        continuation: String?
    ): Pair<List<VideoItem>, String?>? {
        val body = baseBody()
        if (continuation.isNullOrEmpty()) body.put("query", query)
        else body.put("continuation", continuation)
        val root = post("search", body) ?: return null
        return try {
            if (continuation.isNullOrEmpty()) {
                val sections = root.optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: return null
                val collected = collectSearchItems(sections)
                if (collected.first.isEmpty()) null else collected
            } else {
                val items = firstContinuationItems(root) ?: return null
                collectSearchItems(items)
            }
        } catch (e: Exception) {
            Log.w(TAG, "search parse failed", e)
            null
        }
    }

    /** YouTube's own Up next for [videoId], in YouTube's order. */
    suspend fun related(
        videoId: String,
        continuation: String?
    ): Pair<List<VideoItem>, String?>? {
        val body = baseBody()
        if (continuation.isNullOrEmpty()) body.put("videoId", videoId)
        else body.put("continuation", continuation)
        val root = post("next", body) ?: return null
        return try {
            if (continuation.isNullOrEmpty()) {
                val results = root.optJSONObject("contents")
                    ?.optJSONObject("twoColumnWatchNextResults")
                    ?.optJSONObject("secondaryResults")
                    ?.optJSONObject("secondaryResults")
                    ?.optJSONArray("results") ?: return null
                val collected = collectNextItems(results)
                if (collected.first.isEmpty()) null else collected
            } else {
                val items = firstContinuationItems(root) ?: return null
                val collected = collectNextItems(items)
                if (collected.first.isEmpty() && collected.second == null) null else collected
            }
        } catch (e: Exception) {
            Log.w(TAG, "related parse failed", e)
            null
        }
    }

    /**
     * Top-level comments for [videoId] via the watch page's own
     * comments continuation (the token rides inside the /next response's
     * comments engagement panel). Null on any failure — callers fall
     * back to the Worker/Data API path.
     */
    suspend fun comments(videoId: String): List<CommentItem>? {
        val first = post("next", baseBody().put("videoId", videoId)) ?: return null
        val token = findCommentsToken(first, insidePanel = false) ?: return null
        val root = post("next", baseBody().put("continuation", token)) ?: return null
        val items = firstContinuationItems(root) ?: return null
        return try {
            val out = mutableListOf<CommentItem>()
            for (i in 0 until items.length()) {
                val renderer = items.optJSONObject(i)
                    ?.optJSONObject("commentThreadRenderer")
                    ?.optJSONObject("comment")
                    ?.optJSONObject("commentRenderer") ?: continue
                val author = textOf(renderer.optJSONObject("authorText"))
                val text = textOf(renderer.optJSONObject("contentText"))
                if (text.isEmpty()) continue
                val likes = parseCount(
                    renderer.optJSONObject("voteCount")?.optString("simpleText", "")
                )
                val avatar = largestThumbUrl(
                    renderer.optJSONObject("authorThumbnail")?.optJSONArray("thumbnails")
                )
                out.add(CommentItem(author, text, likes, avatar))
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "comments parse failed", e)
            null
        }
    }

    // NOTE: a /player-metadata format list (playerFormats) lived here
    // in build 293 and was removed 2026-10-22: anonymous /player
    // returns no streamingData for real videos (probed WEB =
    // UNPLAYABLE, ANDROID_VR = LOGIN_REQUIRED, embed/iOS = errors),
    // so the list silently never existed. Format lists come from
    // yt-dlp extractions only (fast-first chain in YtDlpManager).

    /**
     * Depth-first hunt for the comments continuation token: it only
     * counts inside the engagement panel whose target is the comments
     * section, so tokens from other panels are never picked up.
     */
    private fun findCommentsToken(node: Any?, insidePanel: Boolean, depth: Int = 0): String? {
        if (node == null || depth > 40) return null
        when (node) {
            is JSONObject -> {
                var inside = insidePanel
                val panel = node.optJSONObject("engagementPanelSectionListRenderer")
                if (panel != null) {
                    inside = panel.optString("targetId", "") == "engagement-panel-comments-section"
                    if (inside) {
                        findCommentsToken(panel, true, depth + 1)?.let { return it }
                        return null
                    }
                }
                if (inside) {
                    node.optJSONObject("continuationItemRenderer")
                        ?.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token", "")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { return it }
                    node.optJSONObject("continuationCommand")
                        ?.optString("token", "")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { return it }
                }
                val names = node.names() ?: return null
                for (i in 0 until names.length()) {
                    findCommentsToken(node.opt(names.getString(i)), inside, depth + 1)
                        ?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findCommentsToken(node.opt(i), insidePanel, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }
}
