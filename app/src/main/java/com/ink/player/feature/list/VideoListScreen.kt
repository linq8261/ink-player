package com.ink.player.feature.list

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    viewModel: VideoListViewModel = hiltViewModel()
) {
    val videos by viewModel.videos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

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
        isLoading = isLoading,
        onVideoClick = onVideoClick,
        onNavigateToSettings = onNavigateToSettings
    )
}

// ── 纯 UI 组件（不依赖 ViewModel，便于 Preview 与测试） ──

@Composable
fun VideoListContent(
    videos: List<VideoInfo>,
    isLoading: Boolean,
    onVideoClick: (String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    // 0=视频 1=最近（暂无独立数据源，复用视频列表） 2=设置
    var selectedTab by rememberSaveable { mutableStateOf(0) }

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
            // 标题区
            Header(videoCount = videos.size)

            // 搜索框（静态占位，搜索功能后续实现）
            SearchBarPlaceholder()

            // 内容区
            when {
                isLoading -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VideoListAccent)
                }

                videos.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "没有找到视频文件",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> VideoGrid(
                    videos = videos,
                    onVideoClick = onVideoClick,
                    modifier = Modifier.weight(1f)
                )
            }

            // 底部导航
            NavigationBar(containerColor = VideoListNavBar) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = "视频") },
                    label = { Text("视频", fontSize = 10.sp) },
                    colors = navItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.History, contentDescription = "最近") },
                    label = { Text("最近", fontSize = 10.sp) },
                    colors = navItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        onNavigateToSettings()
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                    label = { Text("设置", fontSize = 10.sp) },
                    colors = navItemColors()
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
private fun SearchBarPlaceholder() {
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
        Text(
            text = "搜索视频",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun VideoGrid(
    videos: List<VideoInfo>,
    onVideoClick: (String) -> Unit,
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
                onClick = { onVideoClick(video.uri.toString()) },
                isScrolling = isScrolling
            )
        }
    }
}

@Composable
private fun VideoGridItem(
    video: VideoInfo,
    onClick: () -> Unit,
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
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
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
            isLoading = false,
            onVideoClick = {},
            onNavigateToSettings = {}
        )
    }
}
