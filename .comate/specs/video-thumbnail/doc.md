# 视频列表缩略图（第一帧）功能设计

## 1. 需求背景

当前视频列表页（`VideoListScreen`）每个视频条目左侧是一个深色占位块 + 🎬 emoji（`VideoListScreen.kt:148-156`），无法直观预览视频内容。

**目标**：将占位图替换为视频缩略图，**优先取内嵌封面（`getEmbeddedPicture()`），封面不存在时回退取第一帧（`getFrameAtTime(0)`）**，加载中/失败时显示原 🎬 占位。

**概念澄清（第一帧 ≠ 封面）**：
- 第一帧：视频流中解码出的首帧画面，所有视频都有。手机拍摄视频通常只有它。
- 封面（Cover Art）：视频文件*可选*内嵌的独立图片（MP4 `covr` atom / MKV attached picture），多数手机视频没有。
- **策略**：有封面用封面（通常由作者精心设置，展示效果最好）；没有封面用第一帧兜底。

## 2. 技术方案

### 2.1 架构

- 新建 `VideoThumbnailLoader`（util 包）：封装 MediaMetadataRetriever 取帧 + 缩放 + LruCache 缓存，对外暴露 suspend 方法。
- 修改 `VideoListScreen.kt` 的 `VideoListItem`：用 `LaunchedEffect` 在 IO 线程加载缩略图，`mutableStateOf<ImageBitmap?>` 持有结果，加载完成渲染 `Image`。
- 不修改 `VideoInfo` / `VideoListViewModel` / `VideoRepository`（缩略图属于 UI 层表现，保持数据层纯净）。

### 2.2 关键实现

#### 新建 `VideoThumbnailLoader.kt`

```kotlin
package com.ink.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
            val bitmap = getEmbeddedCover(retriever)
                ?: getFirstFrame(retriever)
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
```

注：API 27+ 使用 `getScaledFrameAtTime`（内存友好）；minSdk 24 的低版本回退 `getFrameAtTime` + `createScaledBitmap`（低版本机型视频分辨率通常较低，峰值内存可接受）。

#### 修改 `VideoListItem`（VideoListScreen.kt）

```kotlin
@Composable
private fun VideoListItem(video: VideoInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember(video.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(video.id) {
        thumbnail = VideoThumbnailLoader.load(context, video.uri)
    }

    Card(...) {
        Row(...) {
            // 缩略图区：有帧显示 Image，否则回退 🎬 占位
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 120.dp, height = 72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box( // 保留原占位
                    modifier = Modifier
                        .size(width = 120.dp, height = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A)),
                    contentAlignment = Alignment.Center
                ) { Text(text = "🎬", style = MaterialTheme.typography.headlineLarge) }
            }
            Spacer(...)
            Column(...) { /* 文件名 + 时长 不变 */ }
        }
    }
}
```

## 3. 受影响文件

| 文件 | 类型 | 说明 |
|------|------|------|
| `app/src/main/java/com/ink/player/util/VideoThumbnailLoader.kt` | 新建 | 取帧 + 缩放 + 缓存 |
| `app/src/main/java/com/ink/player/feature/list/VideoListScreen.kt` | 修改 | `VideoListItem` 加载并显示缩略图（新增 import：`Image`、`ContentScale`、`LocalContext`、`mutableStateOf`、`remember`、`LaunchedEffect`、`getValue/setValue`、`ImageBitmap`、`VideoThumbnailLoader`） |

## 4. 边界条件与异常处理

- **解码失败 / 视频损坏 / 权限拒绝**：捕获异常返回 null → 显示 🎬 占位。
- **快速滑动**：`LaunchedEffect(video.id)` 随 item 离开组合自动取消；阻塞解码完成后 `finally` 中 `release()`；缓存命中时直接返回，避免重复解码；`Semaphore(3)` 限制并发解码数。
- **内存**：LruCache（约堆 1/8）+ 目标尺寸缩放（330x186），避免 4K 帧 OOM。
- **`OPTION_CLOSEST_SYNC`**：取最近的同步关键帧（首帧附近），保证可解码；即使非精确 0ms 也是视频首画面。
- **低版本（API 24-26）**：回退 `getFrameAtTime` + `createScaledBitmap`。

## 5. 数据流

```
MediaStore → VideoRepository.scanVideos() → List<VideoInfo>(uri)
    → VideoListItem → LaunchedEffect → VideoThumbnailLoader.load (Dispatchers.IO, Semaphore 限流)
    → MediaMetadataRetriever.setDataSource(contentUri)
    → 优先 getEmbeddedPicture()（封面字节数组 → BitmapFactory 解码 → 等比缩放）
    → 无封面则 getScaledFrameAtTime(0) / getFrameAtTime(0)（第一帧，等比缩放）
    → ImageBitmap → LruCache 缓存 → Compose Image(ContentScale.Crop) 渲染
失败/空 → null → 回退 🎬 占位 Box
```

## 6. 预期结果

1. 列表页每个视频条目左侧显示该视频缩略图：**有内嵌封面显示封面，无封面显示第一帧**（等比缩放后 Crop 填充 120x72dp 圆角区域，不变形）。
2. 滚动列表流畅：IO 异步解码 + LruCache 缓存 + 并发限制，不阻塞主线程。
3. 加载失败/解码异常的视频仍显示原 🎬 占位。
4. 验证方式：`assembleRelease` 构建 → adb 安装 → uiautomator dump 或日志确认无崩溃、无 OOM。

## 7. 性能影响分析（是否会卡顿）

**结论：正常使用不会感知到卡顿；极端场景（大量 4K 视频快速滚动 + 低端机）首次加载个别缩略图会有短暂延迟，但不影响交互、不会 ANR/OOM。**

卡顿风险点与对策：

| 风险点 | 说明 | 对策 |
|--------|------|------|
| 首次进入列表，可见 ~9 个条目同时解码 | MMR 解码是 CPU 密集操作（解码完整帧后缩放） | `Semaphore(3)` 限制并发解码数，其余排队，避免 CPU 抢占主线程渲染 |
| 快速滚动触发大量新解码 | 每滑入一个条目启动一个解码任务 | `LaunchedEffect` 随 item 离屏自动取消；`LruCache` 命中后不再解码 |
| 解码峰值内存 | 低版本 API 26- 需先解全尺寸帧 | API 27+ 用 `getScaledFrameAtTime`；缓存输出 330x186 小图（约 245KB/张） |
| 主线程掉帧 | 解码/IO 不能放主线程 | 全部在 `Dispatchers.IO`，主线程仅做 `Image` 渲染 |

开销量级：单张缩略图解码约几十毫秒级（取决于视频分辨率/编码），3 路并发 + 缓存后，滚动重复经过的条目基本零开销。
