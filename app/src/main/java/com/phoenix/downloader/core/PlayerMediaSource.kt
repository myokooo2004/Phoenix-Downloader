package com.phoenix.downloader.core

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/** Builds the ExoPlayer media source for a resolved stream (merges the
 *  separate video+audio DASH streams when both are present). */
object PlayerMediaSource {
    fun fromStreams(context: Context, streams: YtDlpManager.StreamUrls): MediaSource {
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        val video = factory.createMediaSource(MediaItem.fromUri(streams.videoUrl))
        val audioUrl = streams.audioUrl ?: return video
        val audio = factory.createMediaSource(MediaItem.fromUri(audioUrl))
        return MergingMediaSource(video, audio)
    }
}
