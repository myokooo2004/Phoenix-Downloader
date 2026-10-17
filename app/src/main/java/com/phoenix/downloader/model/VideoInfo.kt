package com.phoenix.downloader.model

/** Step 1 of the URL flow: info fetched from a pasted video URL. */
data class VideoInfo(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val viewCount: String,
    val thumbnailUrl: String?,
    /** Direct playable stream URL, resolved lazily via [YtDlpManager.resolveStreamUrl]. */
    val streamUrl: String? = null
) {
    val durationLabel: String
        get() {
            val m = durationSec / 60
            val s = durationSec % 60
            return "%d:%02d".format(m, s)
        }
}
