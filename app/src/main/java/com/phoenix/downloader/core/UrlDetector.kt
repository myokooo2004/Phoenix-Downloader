package com.phoenix.downloader.core

/**
 * Detects whether a pasted string is a direct video URL from a supported site.
 *
 * Flow (two-step, per spec):
 *  1. [VideoInfo] — [YtDlpManager] fetches thumbnail/title.
 *  2. User taps the download button on the video card ->
 *     [DownloadDialogFragment] (bottom sheet) with Music + Video quality grids.
 *
 * Non-URL text falls through to plain search (browser).
 */
object UrlDetector {

    enum class Site { YOUTUBE, FACEBOOK, TIKTOK }

    private val patterns = listOf(
        Site.YOUTUBE to Regex("""^(?:https?://)?(?:www\.|m\.)?youtube\.com/watch\?.*v=[\w-]{6,}""", RegexOption.IGNORE_CASE),
        Site.YOUTUBE to Regex("""^(?:https?://)?youtu\.be/[\w-]{6,}""", RegexOption.IGNORE_CASE),
        Site.YOUTUBE to Regex("""^(?:https?://)?(?:www\.|m\.)?youtube\.com/shorts/[\w-]{6,}""", RegexOption.IGNORE_CASE),
        Site.FACEBOOK to Regex("""^(?:https?://)?(?:www\.|m\.|web\.)?facebook\.com/.+/videos/\d+""", RegexOption.IGNORE_CASE),
        Site.FACEBOOK to Regex("""^(?:https?://)?fb\.watch/[\w-]+""", RegexOption.IGNORE_CASE),
        Site.TIKTOK to Regex("""^(?:https?://)?(?:www\.|m\.|vm\.)?tiktok\.com/@[^/]+/video/\d+""", RegexOption.IGNORE_CASE),
        Site.TIKTOK to Regex("""^(?:https?://)?vt\.tiktok\.com/[\w/]+""", RegexOption.IGNORE_CASE)
    )

    /** Returns the matched [Site], or null if this is not a direct video URL. */
    fun detect(raw: String): Site? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        return patterns.firstOrNull { (_, regex) -> regex.containsMatchIn(input) }?.first
    }

    /** True when the input looks like any http(s) URL at all (used to decide browser vs search). */
    fun looksLikeUrl(raw: String): Boolean {
        val input = raw.trim()
        return input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
    }
}
