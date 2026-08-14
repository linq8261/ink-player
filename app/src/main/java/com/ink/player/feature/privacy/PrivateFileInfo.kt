package com.ink.player.feature.privacy

data class PrivateFileInfo(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val encryptTime: Long,
    val thumbPath: String? = null // 加密缩略图路径
) {
    val formattedSize: String
        get() {
            val kb = fileSize / 1024f
            return if (kb >= 1024 * 1024) {
                "%.1f GB".format(java.util.Locale.US, kb / 1024 / 1024)
            } else if (kb >= 1024) {
                "%.1f MB".format(java.util.Locale.US, kb / 1024)
            } else {
                "${fileSize} B"
            }
        }
}
