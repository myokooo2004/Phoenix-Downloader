package com.phoenix.downloader.core

import android.content.Context
import com.phoenix.downloader.model.DownloadItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists download queue state to disk so downloads can RESUME
 * after the app is killed or closed for any reason.
 *
 * State is saved on every progress update (throttled) and on pause.
 * On app start, [loadIncomplete] returns items that were DOWNLOADING
 * or QUEUED — the UI can offer to resume them.
 */
object DownloadStore {

    private const val FILE_NAME = "downloads.json"

    private fun stateFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** Save the full list of active downloads. */
    fun save(context: Context, items: List<DownloadItem>) {
        try {
            val arr = JSONArray()
            for (it in items) {
                if (it.status == DownloadItem.Status.DONE) continue
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("url", it.url)
                    put("formatLabel", it.formatLabel)
                    put("isAudio", it.isAudio)
                    put("destPath", it.destPath)
                    put("downloadedBytes", it.downloadedBytes)
                    put("totalBytes", it.totalBytes)
                    put("status", it.status.name)
                })
            }
            stateFile(context).writeText(arr.toString())
        } catch (_: Exception) { /* best effort */ }
    }

    /** Load downloads that did not finish. Returns empty list if none. */
    fun loadIncomplete(context: Context): List<DownloadItem> {
        return try {
            val f = stateFile(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DownloadItem(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    url = o.getString("url"),
                    formatLabel = o.getString("formatLabel"),
                    isAudio = o.getBoolean("isAudio"),
                    destPath = o.getString("destPath"),
                    downloadedBytes = o.getLong("downloadedBytes"),
                    totalBytes = o.getLong("totalBytes"),
                    status = DownloadItem.Status.PAUSED // resume as paused — user taps to continue
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Remove a finished/cancelled download from the persisted state. */
    fun remove(context: Context, id: Long) {
        try {
            val remaining = loadIncomplete(context).filter { it.id != id }
            save(context, remaining)
        } catch (_: Exception) { /* best effort */ }
    }

    /** Clear all persisted state (e.g. user clears downloads). */
    fun clear(context: Context) {
        try { stateFile(context).delete() } catch (_: Exception) { }
    }
}
