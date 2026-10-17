package com.phoenix.downloader.core

import android.content.Context
import com.phoenix.downloader.model.FormatOption
import com.phoenix.downloader.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TEMPORARY STUB — youtubedl-android disabled for base build test.
 * Will be restored once base build succeeds.
 */
object YtDlpManager {

    fun init(context: Context) {
        // Stub
    }

    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        Result.failure(Exception("yt-dlp disabled for base build test"))
    }

    suspend fun fetchFormats(url: String): Result<FormatsResult> = withContext(Dispatchers.IO) {
        Result.failure(Exception("yt-dlp disabled for base build test"))
    }

    suspend fun resolveStreamUrl(url: String): Result<String?> = withContext(Dispatchers.IO) {
        Result.failure(Exception("yt-dlp disabled for base build test"))
    }

    suspend fun checkForUpdates(): Result<Boolean> = withContext(Dispatchers.IO) {
        Result.success(false)
    }

    data class FormatsResult(
        val music: List<FormatOption>,
        val video: List<FormatOption>
    )
}
