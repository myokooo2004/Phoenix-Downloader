package com.phoenix.downloader.core

import android.content.Context
import com.phoenix.downloader.ui.home.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last successful Home trending list, persisted to disk so the next
 * cold start paints instantly (stale-while-revalidate): Home renders this
 * cache (or the bundled snapshot on first install) immediately, then the
 * network refresh replaces it. Capped at 60 items.
 */
object TrendingCache {
    private const val FILE_NAME = "trending_cache.json"
    private const val CAP = 60

    fun load(context: Context): List<VideoItem> {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val out = mutableListOf<VideoItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    VideoItem(
                        o.getString("url"),
                        o.getString("title"),
                        o.optString("thumbnailUrl"),
                        o.optInt("duration")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, videos: List<VideoItem>) {
        try {
            val arr = JSONArray()
            for (v in videos.take(CAP)) {
                arr.put(JSONObject().apply {
                    put("url", v.url)
                    put("title", v.title)
                    put("thumbnailUrl", v.thumbnail)
                    put("duration", v.duration)
                })
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            // Cache is best-effort; a failed write just means a slower
            // next cold start.
        }
    }
}
