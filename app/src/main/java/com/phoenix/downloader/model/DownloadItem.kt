package com.phoenix.downloader.model

/** One row in the Downloads screen (mockup 6). */
data class DownloadItem(
    val id: Long,
    val title: String,
    val url: String,
    val formatLabel: String,   // e.g. "4K HD (MP4)"
    val isAudio: Boolean,
    val destPath: String,
    val durationLabel: String? = null,
    val sizeLabel: String? = null,
    var status: Status = Status.QUEUED,
    var progress: Int = 0,           // 0..100
    var downloadedBytes: Long = 0,
    var totalBytes: Long = 0
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
