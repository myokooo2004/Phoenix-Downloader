package com.phoenix.downloader.core

import android.content.Context
import com.phoenix.downloader.model.DownloadItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists ALL download records (including completed ones) to disk so the
 * Downloads screen survives restarts and unfinished downloads can RESUME
 * after the app is killed — yt-dlp continues from the partial files, which
 * stay in the download directory.
 */
object DownloadStore {

    private const val FILE_NAME = "downloads.json"

    private fun stateFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun saveAll(context: Context, items: List<DownloadItem>) {
        try {
            val arr = JSONArray()
            for (it in items) {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("url", it.url)
                    put("formatLabel", it.formatLabel)
                    put("isAudio", it.isAudio)
                    put("destPath", it.destPath)
                    put("thumbnail", it.thumbnail)
                    put("formatId", it.formatId)
                    put("client", it.client ?: JSONObject.NULL)
                    put("height", it.height)
                    put("container", it.container)
                    put("createdAt", it.createdAt)
                    put("filePath", it.filePath ?: JSONObject.NULL)
                    put("errorMessage", it.errorMessage ?: JSONObject.NULL)
                    put("downloadedBytes", it.downloadedBytes)
                    put("totalBytes", it.totalBytes)
                    put("progress", it.progress)
                    put("status", it.status.name)
                })
            }
            stateFile(context).writeText(arr.toString())
        } catch (_: Exception) { /* best effort */ }
    }

    fun loadAll(context: Context): List<DownloadItem> {
        return try {
            val f = stateFile(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DownloadItem(
                    id = o.getLong("id"),
                    title = o.optString("title", ""),
                    url = o.optString("url", ""),
                    formatLabel = o.optString("formatLabel", ""),
                    isAudio = o.optBoolean("isAudio", false),
                    destPath = o.optString("destPath", ""),
                    thumbnail = o.optString("thumbnail", ""),
                    formatId = o.optString("formatId", ""),
                    client = if (o.isNull("client")) null else o.optString("client", "").ifEmpty { null },
                    height = o.optInt("height", 0),
                    container = o.optString("container", "MP4"),
                    createdAt = o.optLong("createdAt", 0L),
                    filePath = if (o.isNull("filePath")) null else o.optString("filePath", "").ifEmpty { null },
                    errorMessage = if (o.isNull("errorMessage")) null else o.optString("errorMessage", "").ifEmpty { null },
                    downloadedBytes = o.optLong("downloadedBytes", 0),
                    totalBytes = o.optLong("totalBytes", 0),
                    progress = o.optInt("progress", 0),
                    status = runCatching {
                        DownloadItem.Status.valueOf(o.optString("status", "QUEUED"))
                    }.getOrDefault(DownloadItem.Status.QUEUED)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clear(context: Context) {
        try { stateFile(context).delete() } catch (_: Exception) { }
    }
}
