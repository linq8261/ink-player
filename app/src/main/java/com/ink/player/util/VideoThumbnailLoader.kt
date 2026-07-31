package com.ink.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object VideoThumbnailLoader {

    // 缩略图目标尺寸（显示区域 120x72dp，按 ~2.75x 密度取整，防止 4K 帧 OOM）
    private const val TARGET_WIDTH = 330
    private const val TARGET_HEIGHT = 186

    // 内存缓存：约 1/8 应用堆，key = video uri
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4 / 1024 // KB
    }

    // 并发解码限制：MMR 解码是 CPU 密集操作，限制同时解码数，避免快速滚动时抢占 CPU 导致掉帧
    private val decodeSemaphore = Semaphore(3)

    suspend fun load(context: Context, uri: Uri): ImageBitmap? {
        val key = uri.toString()
        return cache.get(key) ?: decodeSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                decodeThumbnail(context, uri)?.also { cache.put(key, it) }
            }
        }
    }

    /**
     * 优先取内嵌封面（getEmbeddedPicture），无封面则回退解码第一帧（getFrameAtTime）。
     * 输出保持宽高比的等比缩放图（不超过 TARGET 尺寸），由 UI 层 ContentScale.Crop 填充显示。
     */
    private fun decodeThumbnail(context: Context, uri: Uri): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bitmap = getEmbeddedCover(retriever) ?: getFirstFrame(retriever)
            bitmap?.asImageBitmap()
        } catch (e: Exception) {
            null // 解码失败/权限不足 → 回退占位
        } finally {
            retriever.release()
        }
    }

    /** 内嵌封面：字节数组 → 解码 → 等比缩放 */
    private fun getEmbeddedCover(retriever: MediaMetadataRetriever): Bitmap? {
        val bytes = retriever.embeddedPicture ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { scaleToFit(it) }
    }

    /** 第一帧：API 27+ 按目标尺寸解码；低版本解码全帧后等比缩放 */
    private fun getFirstFrame(retriever: MediaMetadataRetriever): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                TARGET_WIDTH,
                TARGET_HEIGHT
            )
        } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { scaleToFit(it) }
        }
    }

    /** 保持宽高比缩放到不超过 TARGET 尺寸（原图更小则原样返回） */
    private fun scaleToFit(src: Bitmap): Bitmap {
        val scale = minOf(
            TARGET_WIDTH.toFloat() / src.width,
            TARGET_HEIGHT.toFloat() / src.height
        )
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt(),
            (src.height * scale).toInt(),
            true
        )
    }
}
