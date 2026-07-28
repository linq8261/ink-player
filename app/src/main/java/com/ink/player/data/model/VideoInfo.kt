package com.ink.player.data.model

import android.net.Uri

data class VideoInfo(
    val id: Long,
    val displayName: String,
    val uri: Uri,
    val durationMs: Long,
    val size: Long,
    val mimeType: String,
    val dateAdded: Long
) {
    val durationFormatted: String
        get() {
            val totalSec = durationMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%d:%02d".format(m, s)
        }
}
