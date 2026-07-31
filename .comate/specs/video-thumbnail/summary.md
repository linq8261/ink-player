# 视频列表缩略图（封面优先，回退第一帧）实现总结

## 变更内容

### 1. 新增 `app/src/main/java/com/ink/player/util/VideoThumbnailLoader.kt`
- `object VideoThumbnailLoader`：封装 MediaMetadataRetriever 取图逻辑
- **封面优先**：`getEmbeddedPicture()` 内嵌封面（字节数组 → `BitmapFactory` 解码），为 null 回退第一帧
- **第一帧兜底**：API 27+ 用 `getScaledFrameAtTime(0, OPTION_CLOSEST_SYNC, 330, 186)`；API 24-26 用 `getFrameAtTime` + `scaleToFit`
- **等比缩放** `scaleToFit`：保持宽高比，不超 330x186，UI 层 `ContentScale.Crop` 填充不变形
- **性能保障**：`Dispatchers.IO` 异步解码、`Semaphore(3)` 并发限流、`LruCache`（约堆 1/8）缓存、异常捕获返回 null
- 解码异常（损坏/权限）捕获后返回 null → UI 回退 🎬 占位

### 2. 修改 `app/src/main/java/com/ink/player/feature/list/VideoListScreen.kt`
- `VideoListItem` 新增：`remember(video.id)` 持有 `ImageBitmap?` 状态 + `LaunchedEffect(video.id)` 异步加载
- 缩略图非空 → `Image(bitmap, contentScale = ContentScale.Crop)` 显示 120x72dp 圆角图
- 缩略图为空（加载中/失败）→ 保留原 🎬 深色占位 Box

## 验证结果（真机 3B662601FJY00000）

| 检查项 | 结果 |
|--------|------|
| `assembleRelease` 构建 | ✅ 成功（exit 0） |
| adb 安装 + 启动 | ✅ Success，无崩溃 |
| 首屏列表缩略图 | ✅ 9 个条目 🎬 占位残留 = 0（全部替换为缩略图） |
| 滚动 3 屏后新条目 | ✅ 新滑入 9 个条目同样 0 占位残留（缓存+限流正常） |
| 崩溃/ANR/OOM | ✅ 无 FATAL / AndroidRuntime / OutOfMemory / ANR 日志 |
| 进程状态 | ✅ 存活（PID 32757），内存正常 |

## 说明

- 数据层（`VideoInfo` / `VideoListViewModel` / `VideoRepository`）未改动，缩略图为 UI 层表现。
- 手机拍摄的视频大多无内嵌封面，实际走第一帧分支；带封面的视频优先显示封面。
- 无法通过 UI dump 直接确认图片像素内容（图片非文本控件），验证基于：占位符数量归零（加载失败会显示 🎬）+ 无崩溃/内存异常。肉眼效果请以手机屏幕为准。
