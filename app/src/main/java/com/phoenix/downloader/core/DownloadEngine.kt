package com.phoenix.downloader.core

import com.phoenix.downloader.model.DownloadItem
import com.phoenix.downloader.model.FormatOption

/**
 * Download engine.
 *
 * PLACEHOLDER — real implementation drives the bundled aria2c binary
 * for multi-connection (segmented) downloads, falling back to a single
 * OkHttp connection when the server rejects Range requests.
 *
 * Concurrency policy (per spec): up to 3 files download in parallel;
 * the rest wait in the queue.
 *
 * TODO:
 *  - Ship aria2c per-ABI, extract to private files dir
 *  - `aria2c -x16 -s16 -c <url> -d <dir> -o <name>` for multi-connection
 *  - Parse aria2c RPC / stdout for progress, feed [DownloadItem] updates
 *  - Pause/resume via .aria2 control files
 */
object DownloadEngine {

    const val MAX_CONCURRENT = 3

    interface Listener {
        fun onProgress(item: DownloadItem)
        fun onComplete(item: DownloadItem)
        fun onFailed(item: DownloadItem, reason: String)
    }

    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    /** Enqueue a download: [videoUrl] + chosen [format]. File lands in [destDir]. */
    fun enqueue(videoUrl: String, format: FormatOption, fileName: String, destDir: String): DownloadItem {
        // TODO: resolve the direct stream URL via YtDlpManager, then hand to aria2c
        return DownloadItem(
            id = System.currentTimeMillis(),
            title = fileName,
            url = videoUrl,
            formatLabel = "${format.label} (${format.format})",
            isAudio = format.isAudio,
            destPath = "$destDir/$fileName"
        ).also {
            // TODO: start aria2c or queue when MAX_CONCURRENT slots are full
            listener?.onProgress(it)
        }
    }

    fun pause(itemId: Long) {
        TODO("aria2c pause via RPC")
    }

    fun resume(itemId: Long) {
        TODO("aria2c unpause via RPC")
    }

    fun cancel(itemId: Long) {
        TODO("aria2c remove + delete partial file")
    }
}
