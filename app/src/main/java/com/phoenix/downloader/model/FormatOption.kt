package com.phoenix.downloader.model

/**
 * One selectable row in the download dialog's Music/Video grids
 * (mockup 4). [preselected] marks the default checked option
 * (360P in the mockup).
 */
data class FormatOption(
    val id: String,
    val label: String,      // e.g. "360P", "4K HD", "128K"
    val format: String,     // e.g. "MP4", "WebM", "MP3"
    val sizeLabel: String,  // e.g. "9.71MB"
    val isAudio: Boolean = false,
    val preselected: Boolean = false
) {
    val displayLabel: String get() = "$label ($format)"
}
