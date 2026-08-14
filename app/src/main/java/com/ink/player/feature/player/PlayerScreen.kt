package com.ink.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.app.Activity
import android.media.AudioManager
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import com.ink.player.data.repository.UserPreferencesRepository
import com.ink.player.util.LogUtil
import kotlin.math.absoluteValue

data class ExternalSwipeState(
    val isActive: Boolean = false,
    val scrollOffset: Int = 0
)

@Composable
fun PlayerScreen(
    videoUri: String?,
    onBackClick: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    decryptPassword: String? = null, // 加密视频的解密密码
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val duration by viewModel.duration.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val decryptFailed by viewModel.decryptFailed.collectAsState()
    val isDecrypting by viewModel.isDecrypting.collectAsState()

    // 读取用户配置的 seek 节流间隔
    val context = LocalContext.current
    val preferences = remember { UserPreferencesRepository.getInstance(context) }
    val seekThrottleMs by preferences.seekThrottleMs.collectAsState(initial = 100L)
    val gestureBrightnessEnabled by preferences.gestureBrightnessEnabled.collectAsState(initial = false)
    val gestureVolumeEnabled by preferences.gestureVolumeEnabled.collectAsState(initial = false)

    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val window = remember { (context as? Activity)?.window }

    // 手势控制层 → 时间轴的滑动事件桥梁（作为 State 对象传递，供 Timeline 的 snapshotFlow 追踪）
    val externalSwipeState = remember { mutableStateOf(ExternalSwipeState()) }

    LaunchedEffect(isPlaying) {
        LogUtil.d("PlayerScreen isPlaying: $isPlaying (duration=$duration)")
    }

    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            viewModel.play(videoUri, decryptPassword)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveProgressNow()
            viewModel.player.stop()
            viewModel.cleanupTempFile()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        PlayerTopBar(
            playbackSpeed = playbackSpeed,
            onBackClick = onBackClick,
            onCycleSpeed = { viewModel.cycleSpeed() },
            onCaptureFrame = { viewModel.captureFrame(context) },
            onNavigateToSettings = onNavigateToSettings
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1F)
        ) {
            // 解密进度条
            if (isDecrypting) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "正在解密...",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // 解密失败提示
            if (decryptFailed) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "解密失败",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "密码错误或文件已损坏",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
            // 视频渲染层
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        player = viewModel.player
                        useController = false
                        keepScreenOn = true
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { viewModel.togglePlayPause() }
            ) {
                // 控制层（半透明覆盖层，点击空白处切换播放/暂停）
                GestureControlLayer(
                durationMs = duration,
                currentPositionMs = currentPosition,
                viewModel = viewModel,
                gestureBrightnessEnabled = gestureBrightnessEnabled,
                gestureVolumeEnabled = gestureVolumeEnabled,
                window = window,
                audioManager = audioManager,
                onExternalSwipe = { offset ->
                    externalSwipeState.value = ExternalSwipeState(isActive = true, scrollOffset = offset)
                },
                onExternalSwipeEnd = {
                    externalSwipeState.value = ExternalSwipeState()
                }
            )

                // 进度条（可拖动快速 seek）
                ProgressSeekBar(
                    durationMs = duration,
                    currentPositionMs = currentPosition,
                    onSeek = { pos -> viewModel.seekTo(pos) }
                )
            }
        }

        // 底部时间轴
        Timeline(
            modifier = Modifier.fillMaxWidth(),
            durationMs = duration,
            currentPositionMs = currentPosition,
            seekThrottleMs = seekThrottleMs,
            externalSwipeState = externalSwipeState,
            onSeek = { pos -> viewModel.seekTo(pos) },
            onDragStart = { viewModel.onTimelineDragStart() },
            onDragEnd = { pos -> viewModel.onTimelineDragEnd(pos) }
        )
    }
}

// ── 顶部栏 ──

@Composable
private fun PlayerTopBar(
    playbackSpeed: Float,
    onBackClick: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCaptureFrame: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                )
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "${playbackSpeed}x",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onCycleSpeed)
                .padding(end = 8.dp, top = 12.dp, bottom = 12.dp)
        )

        Text(
            text = "保存帧",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onCaptureFrame)
                .padding(end = 8.dp, top = 12.dp, bottom = 12.dp)
        )

        IconButton(onClick = onNavigateToSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "设置",
                tint = Color.White
            )
        }
    }
}

// ── 透明手势控制层 ──
// 单击 = 切换播放/暂停
// 双击 = 上（seek +5s）、下（seek +10s），以垂直中点划分
// 长按不放 = 上 1/3 4x、中 1/3 3x、下 1/3 2x 倍速播放，松手恢复原速
// 水平滑动 = 按手指 X 位置对应 seek（类似时间轴拖动）
// 垂直滑动 = 左半区调节屏幕亮度、右半区调节媒体音量（需在设置页开启）

@Composable
private fun ColumnScope.GestureControlLayer(
    durationMs: Long,
    currentPositionMs: Long,
    viewModel: PlayerViewModel,
    gestureBrightnessEnabled: Boolean,
    gestureVolumeEnabled: Boolean,
    window: android.view.Window?,
    audioManager: AudioManager?,
    onExternalSwipe: (scrollOffset: Int) -> Unit,
    onExternalSwipeEnd: () -> Unit
) {
    // 与 Timeline 保持一致的刻度密度
    val density = LocalDensity.current
    val pxPerSecond = with(density) { 20.dp.toPx() }

    // 桥接参数供 pointerInput 闭包读取最新值
    var durationState by remember { mutableLongStateOf(durationMs) }
    LaunchedEffect(durationMs) { durationState = durationMs }
    var posState by remember { mutableLongStateOf(currentPositionMs) }
    LaunchedEffect(currentPositionMs) { posState = currentPositionMs }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val centerY = size.height / 2f
                        if (offset.y < centerY) {
                            viewModel.seekBy(5000L)
                        } else {
                            viewModel.seekBy(10000L)
                        }
                    },
                    onTap = { viewModel.togglePlayPause() },
                    onLongPress = { /* 空回调——防止长按后触发 onTap */ }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val thirdY = size.height / 3f
                    val speed = when {
                        down.position.y < thirdY -> 4f
                        down.position.y < thirdY * 2 -> 3f
                        else -> 2f
                    }
                    viewModel.setTemporarySpeed(speed)
                    waitForUpOrCancellation()
                    viewModel.restoreSpeed()
                }
            }
            .pointerInput(gestureBrightnessEnabled, gestureVolumeEnabled, window, audioManager) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startX = down.position.x
                    val startY = down.position.y
                    var lastY = startY
                    var isHorizontal: Boolean? = null
                    var dragStarted = false
                    // 增量式滚动，与 Timeline 1:1 像素映射
                    var lastX = startX
                    var accumulatedScroll = 0

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY

                            // 移动超过阈值后判断方向
                            if (isHorizontal == null && (dx.absoluteValue > 15 || dy.absoluteValue > 15)) {
                                isHorizontal = dx.absoluteValue > dy.absoluteValue
                            }

                            when {
                                isHorizontal == true -> {
                                    if (!dragStarted) {
                                        dragStarted = true
                                        // 以当前播放位置为起点
                                        accumulatedScroll = (posState / 1000f * pxPerSecond).toInt()
                                        lastX = change.position.x
                                    }
                                    // 增量：手指左移(+delta) → seek 前进 → scrollOffset 增大
                                    val deltaX = lastX - change.position.x
                                    lastX = change.position.x
                                    val maxS = ((durationState / 1000f) * pxPerSecond).toInt()
                                        .coerceAtLeast(0)
                                    accumulatedScroll = (accumulatedScroll + deltaX.toInt())
                                        .coerceIn(0, maxS)
                                    onExternalSwipe(accumulatedScroll)
                                }
                                isHorizontal == false && (gestureBrightnessEnabled || gestureVolumeEnabled) -> {
                                    val delta = (change.position.y - lastY) / size.height * 3f
                                    if (startX < size.width / 2f && gestureBrightnessEnabled) {
                                        window?.let { w ->
                                            val lp = w.attributes
                                            val newB = (lp.screenBrightness - delta)
                                                .coerceIn(0.01f, 1f)
                                            if (lp.screenBrightness != newB) {
                                                lp.screenBrightness = newB
                                                w.attributes = lp
                                            }
                                        }
                                    } else if (startX >= size.width / 2f && gestureVolumeEnabled) {
                                        audioManager?.let { am ->
                                            val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                            val newV = (cur - delta * maxV)
                                                .toInt().coerceIn(0, maxV)
                                            if (cur != newV) {
                                                am.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
                                            }
                                        }
                                    }
                                    lastY = change.position.y
                                }
                                else -> { /* 未超阈值，不处理 */ }
                            }
                            change.consume()
                        } else {
                            break
                        }
                    } while (true)

                    if (dragStarted) {
                        onExternalSwipeEnd()
                    }
                }
            }
    )
}

/**
 * 播放进度条 - 可拖动快速 seek
 *
 * 位于时间轴上方，用于快速定位以及查看播放进度。
 * 时间轴则用于精确控制 seek 位置。
 */
@Composable
private fun ProgressSeekBar(
    modifier: Modifier = Modifier,
    durationMs: Long,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit
) {
    val progress = if (durationMs > 0) {
        (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f

    val durationText = if (durationMs > 0) {
        val totalSec = durationMs / 1000
        "${totalSec / 60}:${String.format("%02d", totalSec % 60)}"
    } else ""

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                )
            )
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val seekMs = ((down.position.x / size.width) * durationMs).toLong()
                        .coerceAtLeast(0)
                    onSeek(seekMs)

                    // 跟随手指拖动持续 seek
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            val t = ((change.position.x / size.width) * durationMs).toLong()
                                .coerceAtLeast(0)
                            onSeek(t)
                            change.consume()
                        } else {
                            break
                        }
                    } while (true)
                }
            }
    ) {
        // 视频时长标签（右上角）
        if (durationText.isNotEmpty()) {
            Text(
                text = durationText,
                color = Color(0xFF999999),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 10.dp, top = 2.dp)
            )
        }

        // 进度条轨道（带左右 10dp padding，垂直居中）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(1.dp)
                .align(Alignment.CenterStart)
        ) {
            // 背景轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(0.5.dp))
            )
            // 已播放进度
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(1.dp)
                    .background(Color(0xFFFF4444), RoundedCornerShape(0.5.dp))
            )
        }
    }
}
