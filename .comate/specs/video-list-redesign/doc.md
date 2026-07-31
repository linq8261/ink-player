# 首页 UI 重构（现代多彩风·3列网格）实现设计

## 1. 需求背景

基于已确认的 HTML 原型（`doc/video-list-ui.html`，多轮确认后的最终版），将首页从当前"横向卡片列表"重构为**现代多彩风格**：

- 顶部渐变标题 + 副标题（去掉右上角设置图标）
- 搜索框（毛玻璃样式，**仅 UI 占位，不做搜索功能**）
- 视频 **3 列网格**，卡片 **3:4 比例**，缩略图铺满，名称/时长覆盖在卡片下层
- **底部导航**：视频 / 最近 / 设置

## 2. 确认的 UI 细节（对照 HTML 最终版）

| 区域 | 规格 |
|------|------|
| 背景 | 深色底 `#10101C` + 紫/蓝径向光晕 |
| 标题 | "弄墨播放器" 渐变流光文字（白→紫→青），副标题 `INK PLAYER · 共 N 部视频` |
| 搜索框 | 圆角 16dp 半透明毛玻璃样式 + 放大镜图标 + "搜索视频"占位文字（静态，不可交互） |
| 网格 | `LazyVerticalGrid` 3 列，间距 10dp，卡片 `aspectRatio(3f/4f)` 圆角 14dp |
| 封面 | `VideoThumbnailLoader` 缩略图（封面/首帧）`ContentScale.Crop` 铺满；加载中/失败显示深色占位 |
| 覆盖层 | 底部渐变遮罩（黑 0.82→透明），**文件名** 10sp 常规字重（最多 2 行省略）+ **时长** 黑底徽章（右下角靠右） |
| 底部导航 | Material3 `NavigationBar`，3 项：视频（选中态紫色高亮）/ 最近 / 设置 |

## 3. 技术方案

### 3.1 架构决策

- **不动全局主题**：本次只改列表页视觉，新配色作为列表页局部常量（`Color.kt` 新增一组 `VideoListColors` 或 Screen 内私有常量），避免影响播放页/设置页。
- **拆分纯 UI 组件**：`VideoListScreen`（保持现有签名与 MainActivity 调用不变）负责权限/数据流；新增 `VideoListContent`（纯 UI，不依赖 ViewModel）承载全部界面，便于 `@Preview` 预览与后续测试。
- **底部导航"最近"tab**：暂无独立数据源（无播放历史表），默认**复用视频列表内容**；设置项复用现有 `onNavigateToSettings` 回调进入设置页。
- 搜索框纯静态占位，不绑定输入状态、不过滤数据。

### 3.2 VideoListScreen 结构（重构后）

```
VideoListScreen(viewModel)           // 入口：权限 + 数据流（签名不变）
└── VideoListContent(
        videos, isLoading,
        onVideoClick, onNavigateToSettings
    )
    ├── Box 背景（深色 + 紫/蓝光晕）
    ├── Column
    │   ├── 标题区（渐变标题 + 副标题）
    │   ├── 搜索框占位（静态）
    │   ├── LazyVerticalGrid(3列)   // 卡片 3:4
    │   │   └── VideoGridItem(video) // 缩略图 + 渐变遮罩 + 文件名 + 时长徽章
    │   └── NavigationBar（视频/最近/设置）
```

### 3.3 关键实现要点

**背景光晕**（静态绘制，性能友好）：

```kotlin
Box(Modifier.fillMaxSize().background(Color(0xFF10101C))) {
    // 紫色光晕（右上）
    Box(Modifier.align(Alignment.TopEnd).size(300.dp).offset(x = 80.dp, y = (-120).dp)
        .background(Brush.radialGradient(listOf(Color(0x667C5CFF), Color.Transparent))))
    // 蓝色光晕（左上）
    Box(Modifier.align(Alignment.TopStart).size(260.dp).offset(x = (-100).dp, y = 60.dp)
        .background(Brush.radialGradient(listOf(Color(0x4D4AA8FF), Color.Transparent))))
    Column(Modifier.fillMaxSize()) { /* 内容 */ }
}
```

**渐变标题**（Compose 用 Brush 实现渐变文字）：

```kotlin
Text(
    text = "弄墨播放器",
    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    modifier = Modifier.background(
        Brush.linearGradient(listOf(Color.White, Color(0xFFB8A6FF), Color(0xFF6EE7FF)))
    ).buildAnnotatedStyle... // 实际用：
)
```
简化实现：直接使用 `Text` + `style = TextStyle(brush = Brush.linearGradient(...))`（Compose 1.4+ 支持 `TextStyle(brush)`）。

**网格卡片**：

```kotlin
LazyVerticalGrid(
    columns = GridCells.Fixed(3),
    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp)
) {
    items(videos, key = { it.id }) { video ->
        VideoGridItem(video = video, onClick = { onVideoClick(video.uri.toString()) })
    }
}

@Composable
private fun VideoGridItem(video: VideoInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember(video.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(video.id) { thumbnail = VideoThumbnailLoader.load(context, video.uri) }

    Box(
        modifier = Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1E2E))
            .clickable(onClick = onClick)
    ) {
        // 封面：缩略图铺满
        thumbnail?.let {
            Image(bitmap = it, contentDescription = null,
                  modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // 底部渐变遮罩
        Box(
            Modifier
                .align(Alignment.BottomCenter).fillMaxWidth()
                .height(70.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
        )
        // 文件名（2 行省略，10sp 常规）
        Text(
            text = video.displayName,
            fontSize = 10.sp, fontWeight = FontWeight.Normal, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 24.dp)
        )
        // 时长徽章（右下角）
        Text(
            text = video.durationFormatted,
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
```

**底部导航**：

```kotlin
var selectedTab by rememberSaveable { mutableStateOf(0) } // 0=视频 1=最近 2=设置
NavigationBar(containerColor = Color(0xB310101C)) {
    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
        icon = { Icon(Icons.Filled.VideoLibrary, null) }, label = { Text("视频") })
    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
        icon = { Icon(Icons.Filled.History, null) }, label = { Text("最近") })
    NavigationBarItem(selected = selectedTab == 2, onClick = {
        selectedTab = 2; onNavigateToSettings()
    }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("设置") })
}
```

- "视频"/"最近"：本地 tab 切换；**最近暂时复用同一视频列表**（无数据源）。
- "设置"：触发 `onNavigateToSettings()` 导航到设置页（返回后回到列表页）。

## 4. 受影响文件

| 文件 | 类型 | 说明 |
|------|------|------|
| `app/src/main/java/com/ink/player/feature/list/VideoListScreen.kt` | 重写 | 网格布局 + 新样式 + 底部导航；拆出 `VideoListContent` 纯 UI 组件（签名不变） |
| `app/src/main/java/com/ink/player/ui/theme/Color.kt` | 修改 | 新增列表页配色常量（背景/光晕/强调色），供 Screen 引用 |
| `MainActivity.kt` / `NavRoutes.kt` | 不动 | `VideoListScreen` 签名保持，无需改动 |

## 5. 边界条件与异常处理

- **缩略图加载中/失败**：卡片显示深色占位 `#1E1E2E`（不崩、不空白闪烁）。
- **视频无缩略图**（MMR 返回 null）：同上占位。
- **超长文件名**：2 行省略，`wordBreak = breakAll` 等效（Compose 用 `TextOverflow.Ellipsis` + `maxLines=2`）。
- **空列表**：保留"没有找到视频文件"空态提示。
- **权限**：维持现有 READ_MEDIA_VIDEO 申请流程不变。
- **性能**：3 列网格缩略图加载复用 `VideoThumbnailLoader`（IO 线程 + Semaphore 限流 + LruCache + 离屏取消）。

## 6. 预期结果

1. 首页呈现确认的现代多彩风格：渐变标题、搜索框占位、3 列 3:4 网格、名称/时长覆盖层、底部导航。
2. 点击卡片进入播放页（现有逻辑不变）；底部导航"设置"进入设置页。
3. 搜索框为静态占位（不可交互）；"最近"tab 暂时复用视频列表。
4. 深色视觉仅作用于列表页，播放页/设置页不受影响。
5. 构建 + 真机验证无崩溃、无 OOM，滚动流畅。
