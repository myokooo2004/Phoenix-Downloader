package com.phoenix.downloader.model

/**
 * One download record — queued, active, paused, failed or completed.
 * Persisted by DownloadStore so downloads survive app restarts and can
 * RESUME where they left off (yt-dlp continues from the partial file).
 */
data class DownloadItem(
    val id: Long,
    val title: String,
    val url: String,
    val formatLabel: String,   // e.g. "720P HD (MP4)" or "MP3 192K"
    val isAudio: Boolean,
    var destPath: String,     // directory the file is downloaded into
    val thumbnail: String = "",
    /** Exact yt-dlp format id, or "MP3_<kbps>" for audio entries. */
    val formatId: String = "",
    /** Player client that listed this format (used for the first attempt). */
    val client: String? = null,
    /** Chosen video height (0 = audio). */
    val height: Int = 0,
    /** Output container: "MP4" (video) — audio is always MP3. */
    val container: String = "MP4",
    val createdAt: Long = System.currentTimeMillis(),
    var filePath: String? = null,      // final file once DONE
    var errorMessage: String? = null,
    var status: Status = Status.QUEUED,
    var progress: Int = 0,             // 0..100
    var downloadedBytes: Long = 0,
    var totalBytes: Long = 0,
    // Transient (not persisted): live speed in bytes/sec and ETA seconds.
    var speedBps: Long = 0,
    var etaSec: Long = -1
) {
    enum class Status { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

    val progressLabel: String
        get() = when (status) {
            Status.DOWNLOADING -> "$progress%"
            Status.PAUSED -> "paused"
            Status.DONE -> "done"
            Status.FAILED -> "failed"
            Status.QUEUED -> "queued"
        }
}
