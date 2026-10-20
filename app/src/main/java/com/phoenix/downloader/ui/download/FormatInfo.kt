package com.phoenix.downloader.ui.download

/** One selectable format row in the download dialog. */
data class FormatInfo(val id: String, val label: String, val size: String, val isAudio: Boolean)
