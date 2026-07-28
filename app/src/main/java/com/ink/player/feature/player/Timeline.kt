package com.ink.player.feature.player

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ink.player.util.LogUtil
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 底部时间轴组件
 *
 * @param durationMs       视频总时长（毫秒）
 * @param currentPositionMs 当前播放位置（毫秒）
 * @param onSeek           用户拖动时回调当前中心竖线对应的时间点
 */
@Composable
fun Timeline(
    modifier: Modifier = Modifier,
    durationMs: Long,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: (Long) -> Unit = {},
    seekThrottleMs: Long = 100L,
    externalSwipeState: State<ExternalSwipeState> = remember { mutableStateOf(ExternalSwipeState()) },
) {
    val density = LocalDensity.current

    // 手动管理的水平滚动偏移（替代 horizontalScroll）
    var scrollOffset by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var screenWidthPx by remember { mutableFloatStateOf(0f) }

    // 方案二：seekTo 降频，每 seekThrottleMs 最多调一次，减轻解码压力
    var lastSeekTime by remember { mutableLongStateOf(0L) }

    // 桥接：将 seekThrottleMs（Composable 参数）转为 Compose State，供手势 lamda 读取最新值
    var throttleMsState by remember { mutableLongStateOf(seekThrottleMs) }
    LaunchedEffect(seekThrottleMs) { throttleMsState = seekThrottleMs }

    // 桥接：将 Composable 参数（非 State）转为 Compose MutableState
    var durationState by remember { mutableLongStateOf(durationMs) }
    LaunchedEffect(durationMs) { durationState = durationMs }

    var posState by remember { mutableLongStateOf(currentPositionMs) }
    LaunchedEffect(currentPositionMs) { posState = currentPositionMs }

    // 固定刻度密度 20dp/s
    val pxPerSecond = with(density) { 20.dp.toPx() }

    // 跟随播放位置自动滚动
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            if (!isDragging && screenWidthPx > 0) {
                val maxS = max(0, ((durationState / 1000f) * pxPerSecond).toInt())
                if (maxS > 0) {
                    val target = ((posState / 1000f) * pxPerSecond).toInt()
                        .coerceIn(0, maxS)
                    if (target != scrollOffset) {
                        scrollOffset = target
                    }
                }
            }
        }
    }

    // 监听手势控制层的外部滑动事件，统一由时间轴处理
    // LaunchedEffect 的 key 随 externalSwipeState.value 变化而重启，实现响应式观测
    LaunchedEffect(externalSwipeState.value) {
        val state = externalSwipeState.value
        if (state.isActive) {
            if (!isDragging) {
                isDragging = true
                onDragStart()
                LogUtil.d("Timeline external drag START")
            }
            val maxS = max(0, ((durationState / 1000f) * pxPerSecond).toInt())
            scrollOffset = state.scrollOffset.coerceIn(0, maxS)
            val timeMs = (scrollOffset / pxPerSecond * 1000).toLong()
                .coerceIn(0, durationState)
            val now = System.currentTimeMillis()
            if (now - lastSeekTime >= throttleMsState) {
                lastSeekTime = now
                onSeek(timeMs)
            }
        } else if (isDragging) {
            isDragging = false
            val timeMsAtCenter = (scrollOffset / pxPerSecond * 1000).toLong()
                .coerceIn(0, durationState)
            onDragEnd(timeMsAtCenter)
            LogUtil.d("Timeline external drag END")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF1A1A1A))
            .clipToBounds()
            .onSizeChanged { screenWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        isDragging = true
                        onDragStart()
                        LogUtil.d("Timeline drag START")
                    },
                    onDragEnd = {
                        isDragging = false
                        val timeMsAtCenter = (scrollOffset / pxPerSecond * 1000).toLong()
                            .coerceIn(0, durationState)
                        onDragEnd(timeMsAtCenter)
                        LogUtil.d("Timeline drag END")
                    },
                    onDragCancel = {
                        isDragging = false
                        LogUtil.d("Timeline drag CANCEL")
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val maxS = max(0, ((durationState / 1000f) * pxPerSecond).toInt())
                        val newOffset = (scrollOffset - dragAmount).toInt()
                            .coerceIn(0, maxS)
                        scrollOffset = newOffset  // scrollOffset 立即更新，时间轴 UI 始终流畅
                        val timeMs = (newOffset / pxPerSecond * 1000).toLong()
                            .coerceIn(0, durationState)
                        // 方案二：seekTo 降频，每 seekThrottleMs 最多调一次，减轻解码压力
                        val now = System.currentTimeMillis()
                        if (now - lastSeekTime >= throttleMsState) {
                            lastSeekTime = now
                            onSeek(timeMs)
                        }
                    }
                )
            }
    ) {
        // 刻度内容：Canvas 固定填满父容器，通过坐标偏移实现视觉滚动
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val ch = size.height
                val totalSeconds = if (durationMs > 0) (durationMs / 1000).toInt() else 0
                val screenHalfPx = screenWidthPx / 2f
                val offset = scrollOffset

                // 只绘制当前可见范围的刻度
                val visibleStart = ((offset - screenHalfPx) / pxPerSecond).toInt().coerceAtLeast(0)
                val visibleEnd = ((offset + screenWidthPx - screenHalfPx) / pxPerSecond).toInt()
                    .coerceAtMost(totalSeconds)

                for (s in visibleStart..visibleEnd) {
                    // x 坐标 = 半屏偏移 + 秒位置 - 滚动偏移，实现视觉滚动
                    val x = screenHalfPx + s * pxPerSecond - offset
                    val isMajor = s % 5 == 0 || s == 0
                    val tickHeight = if (isMajor) ch * 0.5f else ch * 0.25f
                    val strokeWidth = if (isMajor) 2f else 1f

                    drawLine(
                        color = Color(0xFF666666),
                        start = Offset(x, ch - tickHeight),
                        end = Offset(x, ch),
                        strokeWidth = strokeWidth
                    )

                    if (isMajor) {
                        drawTimeLabelAbove(s, x)
                    }
                }
            }
        }

        // 屏幕中心的竖线指示器（固定不随滚动移动）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0xFFFF4444))
        )

        // 底部边界线
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF333333))
        )
    }
}

private fun DrawScope.drawTimeLabelAbove(
    seconds: Int,
    x: Float
) {
    val text = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}:${String.format("%02d", seconds % 60)}"
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            "${h}:${String.format("%02d", m)}:${String.format("%02d", s)}"
        }
    }

    val textPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#999999")
        textSize = 12.dp.toPx() // 固定大小，不受刻度密度影响
        isAntiAlias = true
    }

    // 在刻度上方绘制（靠近 Canvas 顶部）
    drawContext.canvas.nativeCanvas.drawText(
        text,
        x - textPaint.measureText(text) / 2f,
        textPaint.textSize * 1.1f,
        textPaint
    )
}
