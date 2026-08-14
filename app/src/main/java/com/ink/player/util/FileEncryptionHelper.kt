package com.ink.player.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件加密/解密工具类
 *
 * 加密流程：将文件移动到加密目录，改名为时间戳 .priv
 * 后续可升级为 AES 加密头部
 */
object FileEncryptionHelper {

    private const val TAG = "EncryptHelper"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.getDefault())

    /**
     * 获取加密目录：/sdcard/InkPlayer/private/
     * 公共外部存储，卸载后保留，需要 MANAGE_EXTERNAL_STORAGE 权限（Android 11+）
     */
    fun getPrivateDir(context: Context): File {
        return File(Environment.getExternalStorageDirectory(), "InkPlayer/private")
    }

    /**
     * 批量加密文件
     * @param videos 待加密的视频列表（Uri + id）
     * @param context 上下文
     * @return 成功加密的数量
     */
    fun encryptFiles(
        videos: List<EncryptTarget>,
        context: Context,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Int {
        val privateDir = getPrivateDir(context)
        LogUtil.d("[$TAG] 开始加密，共 ${videos.size} 个文件，目标目录: ${privateDir.absolutePath}")

        // 1. 确保加密目录和 .nomedia 文件存在
        ensurePrivateDir(privateDir)
        LogUtil.d("[$TAG] 加密目录已就绪")

        var successCount = 0
        val total = videos.size
        for ((index, video) in videos.withIndex()) {
            LogUtil.d("[$TAG] [${index + 1}/$total] 处理文件: id=${video.id}, uri=${video.uri}")

            // 2. 将 content:// URI 解析为真实文件路径
            val sourcePath = getRealPathFromUri(video.uri, context)
            if (sourcePath == null) {
                LogUtil.d("[$TAG]   → 跳过: 无法解析 URI")
                onProgress?.invoke(successCount, total)
                continue
            }
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                LogUtil.d("[$TAG]   → 跳过: 源文件不存在: $sourcePath")
                onProgress?.invoke(successCount, total)
                continue
            }
            LogUtil.d("[$TAG]   → 源文件: $sourcePath (${sourceFile.length()} bytes)")

            // 3. 生成加密文件名：yyyy-MM-dd_HHmmss_SSS.priv
            val targetName = "${dateFormat.format(Date())}.priv"
            val targetFile = File(privateDir, targetName)

            try {
                // 4. 先从源文件提取缩略图字节（此时源文件尚未移动，路径有效）
                val thumbBytes = extractThumbnailBytes(sourcePath)

                // 5. 移动文件到加密目录（优先 renameTo 同分区原子操作，失败回退 copyTo）
                val moved = if (sourceFile.renameTo(targetFile)) {
                    LogUtil.d("[$TAG]   → renameTo 移动成功（同分区）")
                    true
                } else {
                    LogUtil.d("[$TAG]   → renameTo 失败，改用 copyTo（跨分区）")
                    sourceFile.copyTo(targetFile, overwrite = true).exists()
                }
                if (moved) {
                    LogUtil.d("[$TAG]   → 文件已就位")

                    // 6. AES-256-GCM 加密文件头部 16KB
                    LogUtil.d("[$TAG]   → AES 加密文件头部...")
                    CryptoHelper.encryptHeader(targetFile, password)

                    // 7. 保存加密缩略图（使用移动前提取的字节，避免源路径失效问题）
                    LogUtil.d("[$TAG]   → 生成加密缩略图...")
                    thumbBytes?.let { saveEncryptedThumbnail(targetFile, it, password) }

                    // 8. 删除原文件
                    LogUtil.d("[$TAG]   → 删除原文件")
                    val deleted = sourceFile.delete()
                    LogUtil.d("[$TAG]   → 原文件删除: $deleted")

                    // 8. 从系统媒体库删除记录，防止相册/播放器残留
                    deleteFromMediaStore(video.uri, video.id, context)
                    LogUtil.d("[$TAG]   → MediaStore 记录已删除")

                    successCount++
                    LogUtil.d("[$TAG]   → 加密成功")
                } else {
                    LogUtil.d("[$TAG]   → 加密失败: 复制后目标文件不存在")
                }
            } catch (e: Exception) {
                LogUtil.d("[$TAG]   → 加密异常: ${e.message}")
            }
            // 6. 报告进度（当前已完成数 / 总数）
            onProgress?.invoke(successCount, total)
        }
        LogUtil.d("[$TAG] 加密完成: $successCount/$total")
        return successCount
    }

    private fun ensurePrivateDir(dir: File) {
        if (!dir.exists()) {
            val created = dir.mkdirs()
            LogUtil.d("[$TAG] 创建加密目录: ${dir.absolutePath}, 结果: $created")
        }
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) {
            val created = nomedia.createNewFile()
            LogUtil.d("[$TAG] 创建 .nomedia: $created")
        }
    }

    /**
     * 从 content:// URI 获取真实文件路径
     */
    private fun getRealPathFromUri(uri: Uri, context: Context): String? {
        // 已经是文件路径
        if (uri.scheme == "file") {
            LogUtil.d("[$TAG]     URI 已是文件路径: ${uri.path}")
            return uri.path
        }

        // 尝试从 MediaStore 获取
        try {
            val projection = arrayOf(MediaStore.Video.Media.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val path = cursor.getString(index)
                    LogUtil.d("[$TAG]     MediaStore 解析成功: $path")
                    return path
                }
            }
            LogUtil.d("[$TAG]     MediaStore 查询无结果，尝试 DocumentsProvider")
        } catch (e: Exception) {
            LogUtil.d("[$TAG]     MediaStore 解析异常: ${e.message}")
        }

        // 回退：尝试从 DocumentsProvider 获取
        try {
            val docId = android.provider.DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            if (split.size >= 2) {
                val type = split[0]
                val id = split[1]
                val contentUri = when (type) {
                    "primary" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                val selection = "${MediaStore.Video.Media._ID}=?"
                context.contentResolver.query(contentUri, arrayOf(MediaStore.Video.Media.DATA), selection, arrayOf(id), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                        val path = cursor.getString(index)
                        LogUtil.d("[$TAG]     DocumentsProvider 解析成功: $path")
                        return path
                    }
                }
            }
            LogUtil.d("[$TAG]     DocumentsProvider 查询无结果")
        } catch (e: Exception) {
            LogUtil.d("[$TAG]     DocumentsProvider 解析异常: ${e.message}")
        }

        LogUtil.d("[$TAG]     无法解析 URI")
        return null
    }

    /**
     * 从 MediaStore 中删除记录，防止系统相册/播放器残留
     */
    private fun deleteFromMediaStore(uri: Uri, id: Long, context: Context) {
        try {
            val deleted = context.contentResolver.delete(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Video.Media._ID}=?",
                arrayOf(id.toString())
            )
            LogUtil.d("[$TAG]     MediaStore 删除记录: id=$id, 影响行数=$deleted")
        } catch (e: Exception) {
            LogUtil.d("[$TAG]     MediaStore 删除异常: ${e.message}")
        }
    }

    /**
     * 通知系统媒体库重新扫描，确保删除的文件从 MediaStore 中立即移除
     */
    fun notifyMediaScanner(context: Context) {
        val dir = getPrivateDir(context)
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(dir.absolutePath),
            null,
            null
        )
        LogUtil.d("[$TAG]  已通知系统重新扫描媒体库")
    }

    /** 缩略图文件后缀 */
    const val THUMB_SUFFIX = ".thumb"

    /**
     * 从源视频提取首帧缩略图字节（必须在文件移动前调用，源文件路径需有效）
     * @return JPEG 字节数组，提取失败返回 null
     */
    private fun extractThumbnailBytes(sourcePath: String): ByteArray? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourcePath)
            val bitmap = retriever.frameAtTime
                ?: run {
                    LogUtil.d("[$TAG]   → 无法获取视频首帧")
                    return null
                }
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 320, 240, true)
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            baos.toByteArray()
        } catch (e: Exception) {
            LogUtil.d("[$TAG]   → 缩略图生成失败: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 将缩略图字节加密后保存为 .thumb 文件
     */
    private fun saveEncryptedThumbnail(targetFile: File, thumbBytes: ByteArray, password: String) {
        try {
            val thumbFile = File(targetFile.parent, targetFile.nameWithoutExtension + THUMB_SUFFIX)
            thumbFile.writeBytes(thumbBytes)
            CryptoHelper.encryptFullFile(thumbFile, password)
            LogUtil.d("[$TAG]   → 缩略图已加密保存: ${thumbFile.name} (${thumbBytes.size} bytes)")
        } catch (e: Exception) {
            LogUtil.d("[$TAG]   → 缩略图保存失败: ${e.message}")
        }
    }

    /**
     * 解密缩略图到内存，返回字节数组
     */
    fun decryptThumbnail(thumbFile: File, password: String): ByteArray? {
        return try {
            CryptoHelper.decryptFullFile(thumbFile, password)
        } catch (e: Exception) {
            LogUtil.d("[$TAG]  缩略图解密失败: ${e.message}")
            null
        }
    }

    /**
     * 取消加密（解密还原）：解密头部 → 判断原始格式 → 移动回 Pictures 目录
     * @param filePaths 待解密的 .priv 文件路径列表
     * @param context 上下文
     * @param password 密码
     * @return 成功数量
     */
    fun decryptFiles(
        filePaths: List<String>,
        context: Context,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Int {
        val picturesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        if (!picturesDir.exists()) picturesDir.mkdirs()

        var successCount = 0
        val total = filePaths.size
        for ((index, path) in filePaths.withIndex()) {
            LogUtil.d("[$TAG] [${index + 1}/$total] 取消加密: $path")
            val sourceFile = File(path)
            if (!sourceFile.exists()) {
                LogUtil.d("[$TAG]   → 跳过: 文件不存在")
                onProgress?.invoke(successCount, total)
                continue
            }

            try {
                // 1. 解密文件头部
                if (!CryptoHelper.decryptHeader(sourceFile, password)) {
                    LogUtil.d("[$TAG]   → 解密失败，跳过")
                    onProgress?.invoke(successCount, total)
                    continue
                }

                // 2. 判断原始文件格式，确定还原后缀
                val extension = detectMediaExtension(sourceFile)
                val targetName = "ink_${dateFormat.format(Date())}.$extension"
                val targetFile = File(picturesDir, targetName)

                // 3. 移动文件回 Pictures 目录
                val moved = if (sourceFile.renameTo(targetFile)) {
                    LogUtil.d("[$TAG]   → 移动成功: ${targetFile.absolutePath}")
                    true
                } else {
                    LogUtil.d("[$TAG]   → renameTo 失败，改用 copyTo")
                    sourceFile.copyTo(targetFile, overwrite = true).exists().also {
                        if (it) sourceFile.delete()
                    }
                }

                if (moved) {
                    // 4. 删除对应的加密缩略图
                    val thumbFile = File(sourceFile.parent, sourceFile.nameWithoutExtension + THUMB_SUFFIX)
                    if (thumbFile.exists()) thumbFile.delete()

                    // 5. 通知系统重新扫描，让新文件出现在相册
                    notifyMediaScannerForFile(context, targetFile)

                    successCount++
                    LogUtil.d("[$TAG]   → 取消加密成功")
                }
            } catch (e: Exception) {
                LogUtil.d("[$TAG]   → 取消加密异常: ${e.message}")
            }
            onProgress?.invoke(successCount, total)
        }
        LogUtil.d("[$TAG] 取消加密完成: $successCount/$total")
        return successCount
    }

    /**
     * 通过文件头魔数判断媒体文件格式
     */
    private fun detectMediaExtension(file: File): String {
        return try {
            val header = ByteArray(12)
            java.io.RandomAccessFile(file, "r").use { it.read(header) }

            // JPEG: FFD8
            if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return "jpg"

            // PNG: 89504E47
            if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
            ) return "png"

            // GIF: 474946
            if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte()) return "gif"

            // WebP: 52494646 + WEBP
            if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte()
            ) return "webp"

            // MP4: 前 4 字节 ftyp (在 offset 4)
            if (header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                header[6] == 0x79.toByte() && header[7] == 0x70.toByte()
            ) return "mp4"

            // 默认 mp4
            "mp4"
        } catch (e: Exception) {
            "mp4"
        }
    }

    /**
     * 通知系统重新扫描单个文件
     */
    private fun notifyMediaScannerForFile(context: Context, file: File) {
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null,
            null
        )
    }

    data class EncryptTarget(
        val uri: Uri,
        val id: Long
    )
}
