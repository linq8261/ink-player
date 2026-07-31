# 视频列表缩略图（封面优先，回退第一帧）实现任务

- [x] Task 1: 新建 VideoThumbnailLoader.kt（缩略图取图工具）
    - 1.1: 定义 `VideoThumbnailLoader` object：目标尺寸常量 TARGET_WIDTH=330 / TARGET_HEIGHT=186
    - 1.2: 实现 LruCache 缓存（约堆 1/8，sizeOf 按 KB 计算）
    - 1.3: 实现 `load(context, uri)`：缓存命中直接返回，否则经 `Semaphore(3)` 限流后在 `Dispatchers.IO` 解码
    - 1.4: 实现 `decodeThumbnail`：MMR setDataSource 后**优先 `getEmbeddedPicture()` 解码封面，为 null 时回退第一帧**
    - 1.5: 实现 `getFirstFrame`：API 27+ 用 `getScaledFrameAtTime(0, OPTION_CLOSEST_SYNC)`，低版本 `getFrameAtTime` + `scaleToFit`
    - 1.6: 实现 `scaleToFit`：保持宽高比等比缩放，不超 TARGET 尺寸
    - 1.7: try/catch 捕获解码异常返回 null，finally 中 `release()` 释放 MMR

- [x] Task 2: 修改 VideoListScreen.kt（VideoListItem 加载并显示缩略图）
    - 2.1: 新增 import：`Image`、`ContentScale`、`LocalContext`、`mutableStateOf`、`setValue`、`ImageBitmap`、`LaunchedEffect`、`VideoThumbnailLoader`
    - 2.2: VideoListItem 中 `remember(video.id)` 持 `ImageBitmap?` 状态，`LaunchedEffect(video.id)` 调 `VideoThumbnailLoader.load` 异步加载
    - 2.3: 缩略图非空时用 `Image(bitmap, contentScale = ContentScale.Crop)` 填充 120x72dp 圆角区域
    - 2.4: 缩略图为空（加载中/失败）保留原 🎬 占位 Box，文件名字号/时长展示逻辑不变

- [x] Task 3: 构建并真机验证
    - 3.1: `assembleRelease` 构建通过
    - 3.2: adb 安装到手机并启动，进入视频列表页
    - 3.3: 观察列表缩略图显示（有封面显示封面、无封面显示首帧），滚动列表无卡顿/崩溃，失败条目回退 🎬
