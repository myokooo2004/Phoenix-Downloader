package com.phoenix.downloader.core

import android.content.Context
import org.json.JSONArray

/**
 * The user's own recent searches (most recent first, max 12), shown as
 * chips on the search screen. Stored locally only.
 */
object SearchHistoryStore {
    private const val PREFS = "search_history"
    private const val KEY = "terms"
    private const val MAX = 12

    fun all(context: Context): List<String> {
        return try {
            val arr = JSONArray(prefs(context).getString(KEY, "[]") ?: "[]")
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        val list = all(context).toMutableList()
        list.removeAll { it.equals(t, ignoreCase = true) }
        list.add(0, t)
        try {
            val arr = JSONArray()
            list.take(MAX).forEach { arr.put(it) }
            prefs(context).edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            // Best-effort only.
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
