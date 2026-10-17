package com.phoenix.downloader.core

import com.phoenix.downloader.model.FormatOption
import com.phoenix.downloader.model.VideoInfo

/**
 * Wrapper around the bundled yt-dlp binary.
 *
 * STUB — the real implementation shells out to the yt-dlp binary
 * (shipped per-ABI under app/src/main/jniLibs or extracted to the
 * app's private files dir). Binary integration lands in a later build;
 * until then these return deterministic mock data so the full
 * two-step flow (URL -> video info -> quality dialog) is testable.
 *
 * TODO:
 *  - Extract yt-dlp binary for the device ABI on first run
 *  - `yt-dlp --dump-json --no-download <url>` -> parse into [VideoInfo]
 *  - `yt-dlp -F --print formats <url>` -> parse into [FormatOption] lists
 *  - Auto-update: check GitHub Releases for a newer yt-dlp build,
 *    verify SHA-256, replace the binary in the private update folder
 */
object YtDlpManager {

    /** Step 1: resolve a video URL to its info (thumbnail, title, duration). */
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> {
        // TODO: replace with real `yt-dlp --dump-json` call
        return Result.success(
            VideoInfo(
                url = url,
                title = "Sample video title",
                uploader = "Sample channel",
                durationSec = 170,
                viewCount = "10M",
                thumbnailUrl = null // TODO: real thumbnail URL from yt-dlp JSON
            )
        )
    }

    /**
     * Step 2: list downloadable formats, split into music (audio-only)
     * and video (video+audio / video-only) options.
     */
    suspend fun fetchFormats(url: String): Result<FormatsResult> {
        // TODO: replace with real `yt-dlp -F` parsing
        val music = listOf(
            FormatOption(id = "m4a-128", label = "128K", format = "M4A", sizeLabel = "2.75MB", isAudio = true),
            FormatOption(id = "mp3-128", label = "128K", format = "MP3", sizeLabel = "2.75MB", isAudio = true),
            FormatOption(id = "mp3-48", label = "48K", format = "MP3", sizeLabel = "1.13MB", isAudio = true),
            FormatOption(id = "mp3-256", label = "256K", format = "MP3", sizeLabel = "2.88MB", isAudio = true)
        )
        val video = listOf(
            FormatOption(id = "v144", label = "144P", format = "MP4", sizeLabel = "4.65MB"),
            FormatOption(id = "v240", label = "240P", format = "MP4", sizeLabel = "6.87MB"),
            FormatOption(id = "v360", label = "360P", format = "MP4", sizeLabel = "9.71MB", preselected = true),
            FormatOption(id = "v480", label = "480P", format = "MP4", sizeLabel = "18.78MB"),
            FormatOption(id = "v720", label = "720P HD", format = "MP4", sizeLabel = "34.14MB"),
            FormatOption(id = "v1080", label = "1080P HD", format = "MP4", sizeLabel = "58.73MB"),
            FormatOption(id = "v2k", label = "2K HD", format = "WebM", sizeLabel = "56.38MB"),
            FormatOption(id = "v4k", label = "4K HD", format = "MP4", sizeLabel = "107.70MB")
        )
        return Result.success(FormatsResult(music = music, video = video))
    }

    data class FormatsResult(
        val music: List<FormatOption>,
        val video: List<FormatOption>
    )

    /**
     * Resolve a direct playable stream URL for in-app playback (ExoPlayer).
     * TODO: real implementation runs `yt-dlp -g -f "bv*+ba/b" <url>`
     * and returns the first stream URL. Returns null until the binary lands.
     */
    suspend fun resolveStreamUrl(url: String): Result<String?> {
        // TODO: replace with real `yt-dlp -g` call
        return Result.success(null)
    }
}
