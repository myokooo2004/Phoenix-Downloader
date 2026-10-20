package com.phoenix.downloader.core

import android.content.Context
import org.json.JSONArray

/**
 * The last Hot Searches list received from the Worker (composed there
 * from YouTube's own suggestion rankings), so the section renders
 * instantly and survives offline. There are deliberately NO built-in
 * fallback terms: with no cached list the section hides instead of
 * showing anything that did not come from YouTube.
 */
object HotSearchCache {
    private const val PREFS = "hot_searches"
    private const val KEY = "terms"

    fun load(context: Context): List<String> {
        return try {
            val arr = JSONArray(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]") ?: "[]"
            )
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, terms: List<String>) {
        try {
            val arr = JSONArray()
            terms.forEach { arr.put(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            // Best-effort only.
        }
    }
}
