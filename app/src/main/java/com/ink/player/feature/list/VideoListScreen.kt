package com.ink.player.feature.list

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ink.player.data.model.VideoInfo
import com.ink.player.ui.theme.InkPlayerTheme
import com.ink.player.ui.theme.VideoListAccent
import com.ink.player.ui.theme.VideoListBackground
import com.ink.player.ui.theme.VideoListCardPlaceholder
import com.ink.player.ui.theme.VideoListGlowBlue
import com.ink.player.ui.theme.VideoListGlowPurple
import com.ink.player.ui.theme.VideoListNavBar
import com.ink.player.ui.theme.VideoListTitleGradient
import com.ink.player.util.VideoThumbnailLoader

@Composable
fun VideoListScreen(
    onVideoClick: (String) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onEncryptClick: (List<VideoInfo>) -> String? = { null }, // 返回 null 表示取消，否则返回密码
    externalEncryptPassword: String? = null, // 外部传入的加密密码，非 null 时触发加密
    onPasswordConsumed: () -> Unit = {}, // 密码消费后的回调
    viewModel: VideoListViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val videos by viewModel.videos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val recentVideos by viewModel.recentVideos.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val encryptingCount by viewModel.encryptingCount.collectAsState()
    val encryptedCount by viewModel.encryptedCount.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.loadVideos()
    }

    LaunchedEffect(Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(permission)
    }

    VideoListContent(
        videos = videos,
        recentVideos = recentVideos,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        isSelectionMode = isSelectionMode,
        selectedIds = selectedIds,
        onVideoClick = onVideoClick,
        onNavigateToSettings = onNavigateToSettings,
        onRefresh = { viewModel.refreshVideos() },
        onEncryptClick = {
            val password = onEncryptClick(viewModel.getSelectedVideos())
            if (password != null) {
                viewModel.encryptSelectedVideos(password)
            }
        },
        onEnterSelectionMode = { viewModel.enterSelectionMode(it) },
        onToggleSelection = { viewModel.toggleSelection(it) },
        onSelectAll = { viewModel.selectAll(it) },
        onDeselectAll = { viewModel.deselectAll() },
        onClearSelection = { viewModel.clearSelection() }
    )

    // 外部传入加密密码时触发加密（由 VERIFY_PASSWORD 页面驱动）
    LaunchedEffect(externalEncryptPassword) {
        val pwd = externalEncryptPassword ?: return@LaunchedEffect
        viewModel.encryptSelectedVideos(pwd)
        onPasswordConsumed()
    }

    // 加密进度对话框
    if (encryptingCount > 0) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { /* 不可手动关闭 */ },
            modifier = Modifier.background(VideoListBackground, RoundedCornerShape(20.dp)),
            containerColor = Color(0xFF1A1A2E),
            title = null,
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = VideoListAccent,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "正在加密...",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$encryptedCount / $encryptingCount",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {}
        )
    }
}

// ── 纯 UI 组件（不依赖 ViewModel，便于 Preview 与测试） ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoListContent(
    videos: List<VideoInfo>,
    recentVideos: List<VideoInfo>,
    isLoading: Boolean,
    isRefreshing: Boolean = false,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onVideoClick: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onEncryptClick: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onEnterSelectionMode: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: (List<Long>) -> Unit,
    onDeselectAll: () -> Unit,
    onClearSelection: () -> Unit
) {
    // 0=视频 1=最近 2=设置
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // 搜索：常驻输入框，输入即过滤当前 tab 列表（空关键字 = 非搜索态）
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val isSearching = searchQuery.isNotBlank()
    val searchSource = if (selectedTab == 1) recentVideos else videos
    val searchResults = remember(searchQuery, searchSource) {
        searchSource.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
    }

    // 当前显示的列表
    val displayList = when {
        isSearching -> searchResults
        selectedTab == 1 -> recentVideos
        else -> videos
    }
    val allIds = remember(displayList) { displayList.map { it.id } }
    val isAllSelected = allIds.isNotEmpty() && selectedIds.containsAll(allIds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VideoListBackground)
    ) {
        // 背景光晕（静态绘制）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(300.dp)
                .offset(x = 80.dp, y = (-120).dp)
                .background(Brush.radialGradient(listOf(VideoListGlowPurple, Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(260.dp)
                .offset(x = (-100).dp, y = 60.dp)
                .background(Brush.radialGradient(listOf(VideoListGlowBlue, Color.Transparent)))
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // 标题区（多选模式下显示选择栏）
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    isAllSelected = isAllSelected,
                    onToggleAll = {
                        if (isAllSelected) onDeselectAll()
                        else onSelectAll(allIds)
                    },
                    onCancel = onClearSelection
                )
            } else {
                Header(videoCount = videos.size)
            }

            // 搜索框（多选模式下隐藏）
            if (!isSelectionMode) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it }
                )
            }

            // 内容区（支持下拉刷新）
            val pullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                when {
                    isSearching && searchResults.isEmpty() -> EmptyHint(
                        text = "未找到相关视频",
                        modifier = Modifier.fillMaxSize()
                    )

                    isSearching -> VideoGrid(
                        videos = searchResults,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onVideoClick = onVideoClick,
                        onEnterSelectionMode = onEnterSelectionMode,
                        onToggleSelection = onToggleSelection,
                        modifier = Modifier.fillMaxSize()
                    )

                    selectedTab == 1 && recentVideos.isEmpty() -> EmptyHint(
                        text = "暂无最近播放",
                        modifier = Modifier.fillMaxSize()
                    )

                    selectedTab == 1 -> VideoGrid(
                        videos = recentVideos,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onVideoClick = onVideoClick,
                        onEnterSelectionMode = onEnterSelectionMode,
                        onToggleSelection = onToggleSelection,
                        modifier = Modifier.fillMaxSize()
                    )

                    videos.isEmpty() -> EmptyHint(
                        text = "没有找到视频文件",
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> VideoGrid(
                        videos = videos,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onVideoClick = onVideoClick,
                        onEnterSelectionMode = onEnterSelectionMode,
                        onToggleSelection = onToggleSelection,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 底部：多选模式显示操作栏，否则显示导航栏
            if (isSelectionMode) {
                SelectionBottomBar(
                    selectedCount = selectedIds.size,
                    onEncrypt = onEncryptClick
                )
            } else {
                BottomNavBar(
                    selectedTab = selectedTab,
                    onTabChange = { tab ->
                        selectedTab = tab
                        if (tab == 2) onNavigateToSettings()
                    }
                )
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = VideoListAccent,
    selectedTextColor = VideoListAccent,
    indicatorColor = VideoListAccent.copy(alpha = 0.18f),
    unselectedIconColor = Color.White.copy(alpha = 0.4f),
    unselectedTextColor = Color.White.copy(alpha = 0.4f)
)

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun Header(videoCount: Int) {
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = "弄墨播放器",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                brush = Brush.linearGradient(VideoListTitleGradient)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "INK PLAYER · 共 $videoCount 部视频",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 12.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(VideoListAccent),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索视频",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "清除",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    videos: List<VideoInfo>,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onVideoClick: (String) -> Unit,
    onEnterSelectionMode: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val isScrolling = gridState.isScrollInProgress

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(videos, key = { it.id }) { video ->
            VideoGridItem(
                video = video,
                isSelected = video.id in selectedIds,
                isSelectionMode = isSelectionMode,
                onClick = { onVideoClick(video.uri.toString()) },
                onLongClick = { onEnterSelectionMode(video.id) },
                onToggleSelect = { onToggleSelection(video.id) },
                isScrolling = isScrolling
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGridItem(
    video: VideoInfo,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    isScrolling: Boolean
) {
    val context = LocalContext.current
    var thumbnail by remember(video.id) { mutableStateOf<ImageBitmap?>(null) }

    // 滚动中不加载封面/首帧缩略图，滚动停止后才加载（离屏自动取消，缓存命中零开销）
    LaunchedEffect(video.id, isScrolling) {
        if (!isScrolling) {
            thumbnail = VideoThumbnailLoader.load(context, video.uri)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(VideoListCardPlaceholder)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) VideoListAccent else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onToggleSelect() else onClick()
                },
                onLongClick = {
                    if (!isSelectionMode) onLongClick()
                }
            )
    ) {
        // 封面：缩略图铺满（加载中/失败显示深色占位）
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // 选中遮罩
        if (isSelected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(VideoListAccent.copy(alpha = 0.25f))
            )
        }

        // 选择模式下右上角 CheckBox
        if (isSelectionMode) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = if (isSelected) "已选中" else "未选中",
                tint = if (isSelected) VideoListAccent else Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
            )
        }

        // 底部渐变遮罩
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(70.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                    )
                )
        )

        // 名称 + 时长覆盖层（卡片下层）
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = video.displayName,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                text = video.durationFormatted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.End)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 0.dp)
            )
        }
    }
}

// ── 多选模式 UI ──

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    isAllSelected: Boolean,
    onToggleAll: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "取消",
                tint = Color.White
            )
        }

        Text(
            text = "已选 $selectedCount 项",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        TextButton(onClick = onToggleAll) {
            Icon(
                imageVector = if (isAllSelected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = "全选",
                tint = VideoListAccent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isAllSelected) "取消全选" else "全选",
                color = VideoListAccent,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    onEncrypt: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VideoListNavBar)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onEncrypt,
            enabled = selectedCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = VideoListAccent,
                disabledContainerColor = VideoListAccent.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "加密",
                fontSize = 14.sp
            )
        }
    }
}

// ── 底部导航 ──

@Composable
private fun BottomNavBar(
    selectedTab: Int,
    onTabChange: (Int) -> Unit
) {
    NavigationBar(containerColor = VideoListNavBar) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabChange(0) },
            icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = "视频") },
            label = { Text("视频", fontSize = 10.sp) },
            colors = navItemColors()
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabChange(1) },
            icon = { Icon(Icons.Filled.History, contentDescription = "最近") },
            label = { Text("最近", fontSize = 10.sp) },
            colors = navItemColors()
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { onTabChange(2) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
            label = { Text("设置", fontSize = 10.sp) },
            colors = navItemColors()
        )
    }
}

// ── Preview ──

@Preview(showBackground = true, backgroundColor = 0xFF10101C)
@Composable
private fun VideoListContentPreview() {
    InkPlayerTheme(darkTheme = true, dynamicColor = false) {
        VideoListContent(
            videos = listOf(
                VideoInfo(1, "Record_2026-07-31-01-11-08_8c0c.mp4", Uri.parse("content://media/external/video/media/1"), 1_356_000, 128, "video/mp4", 0),
                VideoInfo(2, "Record_2026-07-30-04-35-51_2332.mp4", Uri.parse("content://media/external/video/media/2"), 14_121_000, 1, "video/mp4", 0),
                VideoInfo(3, "lv_0_20260730153627.mp4", Uri.parse("content://media/external/video/media/3"), 12_000, 1, "video/mp4", 0),
                VideoInfo(4, "20260403_212340914.mp4", Uri.parse("content://media/external/video/media/4"), 5_000, 1, "video/mp4", 0),
                VideoInfo(5, "dreamina_7624495184792915262_m.mp4", Uri.parse("content://media/external/video/media/5"), 15_000, 1, "video/mp4", 0),
                VideoInfo(6, "1785475192450..mp4", Uri.parse("content://media/external/video/media/6"), 10_000, 1, "video/mp4", 0)
            ),
            recentVideos = listOf(
                VideoInfo(3, "lv_0_20260730153627.mp4", Uri.parse("content://media/external/video/media/3"), 12_000, 1, "video/mp4", 0),
                VideoInfo(1, "Record_2026-07-31-01-11-08_8c0c.mp4", Uri.parse("content://media/external/video/media/1"), 1_356_000, 128, "video/mp4", 0)
            ),
            isLoading = false,
            isRefreshing = false,
            isSelectionMode = false,
            selectedIds = emptySet(),
            onVideoClick = {},
            onNavigateToSettings = {},
            onEnterSelectionMode = {},
            onToggleSelection = {},
            onSelectAll = {},
            onDeselectAll = {},
            onClearSelection = {}
        )
    }
}
