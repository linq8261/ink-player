package com.ink.player.feature.wukong

import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WUKONG_SCREEN"

/**
 * 悟空浏览器下载视频转 mp4。
 *
 * 源目录：/sdcard/Android/data/com.cat.readall/files/Download/m3u8（需零宽字符绕过访问）
 * 输出目录：公共 Download/wukong/
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WukongConvertScreen(onBackClick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var tasks by remember { mutableStateOf<List<VideoTask>>(emptyList()) }
    var pendingTask by remember { mutableStateOf<VideoTask?>(null) }
    var convertingTask by remember { mutableStateOf<VideoTask?>(null) }
    var progressText by remember { mutableStateOf("") }
    var progressFraction by remember { mutableStateOf(0f) }
    var doneSegments by remember { mutableStateOf(0) }
    var totalSegments by remember { mutableStateOf(0) }
    var segmentInner by remember { mutableStateOf(0f) }
    var cancelled by remember { mutableStateOf(false) }

    /**
     * 重新扫描源目录与输出目录，刷新列表与置灰状态。
     */
    fun refresh() {
        scope.launch {
            isLoading = true
            errorMsg = null
            val result = withContext(Dispatchers.IO) {
                if (!hasAllFilesAccess()) {
                    Result.failure(IllegalStateException("缺少\"所有文件访问权限\""))
                } else {
                    WukongScanner.scan(context)
                }
            }
            isLoading = false
            result.fold(
                onSuccess = { tasks = it },
                onFailure = {
                    errorMsg = it.message ?: it.toString()
                    Log.e(TAG, "扫描失败", it)
                }
            )
        }
    }

    /**
     * 用「已完成分片数 + 当前分片内进度」合成整体进度（0~1），并刷新文本。
     */
    fun updateProgress() {
        val fraction = if (totalSegments > 0)
            ((doneSegments + segmentInner) / totalSegments.toFloat()).coerceIn(0f, 1f)
        else 0f
        progressFraction = fraction
        val pct = (fraction * 100).toInt()
        val curSeg = (doneSegments + 1).coerceAtMost(totalSegments.coerceAtLeast(1))
        progressText = if (totalSegments > 0) "$pct% · 第 $curSeg/$totalSegments 片" else "$pct%"
    }

    // 进入页面先检查权限，有权限则自动扫描
    LaunchedEffect(Unit) {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(context)
        } else {
            refresh()
        }
    }

    // 从系统设置页返回时，若权限已授予则自动扫描
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasAllFilesAccess()) {
                if (tasks.isEmpty() && errorMsg == null && !isLoading) refresh()
            }
        }
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("悟空浏览器下载视频转mp4") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 输出目录提示
            Text(
                text = "输出目录：Download/$OUTPUT_DIR_NAME/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            HorizontalDivider(thickness = 0.5.dp)

            when {
                isLoading && tasks.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }

                errorMsg != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = errorMsg ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            if (!hasAllFilesAccess()) requestAllFilesAccess(context) else refresh()
                        }) {
                            Text(if (hasAllFilesAccess()) "重试" else "去授权")
                        }
                    }
                }

                tasks.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "未找到可转换的视频",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(tasks, key = { it.tsDirPath }) { task ->
                            VideoTaskItem(
                                task = task,
                                isConverting = convertingTask?.name == task.name,
                                onClick = {
                                    if (!task.converted && convertingTask == null) {
                                        pendingTask = task
                                    }
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }

        // 转换确认弹窗
        pendingTask?.let { task ->
            AlertDialog(
                onDismissRequest = { pendingTask = null },
                title = { Text("确认转换") },
                text = {
                    Text(
                        "将「${task.name}」转换为 mp4？\n\n" +
                            "分片 ${task.tsCount} 个 · ${formatSize(task.totalSize)}" +
                            if (task.encrypted) " · 需解密" else ""
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingTask = null
                        convertingTask = task
                        cancelled = false
                        progressFraction = 0f
                        progressText = ""
                        doneSegments = 0
                        totalSegments = 0
                        segmentInner = 0f
                        scope.launch {
                            doConvert(
                                task = task,
                                context = context,
                                scope = scope,
                                onProgress = { cur, total ->
                                    doneSegments = cur
                                    totalSegments = total
                                    segmentInner = 0f
                                    updateProgress()
                                },
                                onSegmentProgress = { inner ->
                                    segmentInner = inner
                                    updateProgress()
                                },
                                isCancelled = { cancelled },
                                onDone = { ok, msg ->
                                    convertingTask = null
                                    progressText = ""
                                    val finalMsg = if (!ok && cancelled) "已取消转换" else msg
                                    android.widget.Toast
                                        .makeText(context, finalMsg, android.widget.Toast.LENGTH_LONG)
                                        .show()
                                    cancelled = false
                                    if (ok) refresh() // 刷新置灰状态
                                }
                            )
                        }
                    }) {
                        Text("转换")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingTask = null }) {
                        Text("取消")
                    }
                }
            )
        }

        // 转换中进度弹窗
        convertingTask?.let { task ->
            AlertDialog(
                onDismissRequest = { /* 转换中不可关闭 */ },
                title = { Text("正在转换") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = task.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (progressText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { cancelled = true }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

/**
 * 执行转换：转 mp4 → 写入媒体库 → 回调结果。
 */
private suspend fun doConvert(
    task: VideoTask,
    context: android.content.Context,
    scope: CoroutineScope,
    onProgress: (Int, Int) -> Unit,
    onSegmentProgress: (Float) -> Unit = {},
    onDone: (Boolean, String) -> Unit,
    isCancelled: () -> Boolean
) {
    val result: Result<File> = withContext(Dispatchers.IO) {
        val outDir = ensureOutputDir(context)
        val outFile = File(outDir, "${task.name}.mp4")
        Log.d(TAG, "输出文件: ${outFile.absolutePath}")

        runCatching {
            M3u8Converter.convert(
                task = task,
                outputFile = outFile,
                onProgress = { cur, total ->
                    scope.launch(Dispatchers.Main) { onProgress(cur, total) }
                },
                onSegmentProgress = { inner ->
                    scope.launch(Dispatchers.Main) { onSegmentProgress(inner) }
                },
                isCancelled = isCancelled
            )
        }
    }

    result.fold(
        onSuccess = { file ->
            // 发布到公共媒体库（Movies/wukong/），成功后删除私有临时文件
            val published = publishToMediaStore(context, file)
            val msg = if (published) {
                "转换成功：${file.name}"
            } else {
                "转换成功（未发布到媒体库）：${file.name}"
            }
            Log.d(TAG, msg)
            onDone(true, msg)
        },
        onFailure = { e ->
            Log.e(TAG, "转换失败", e)
            onDone(false, "转换失败：${e.message ?: e.toString()}")
        }
    )
}

@Composable
private fun VideoTaskItem(
    task: VideoTask,
    isConverting: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (task.converted) 0.4f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (task.converted) Color.Gray.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = !task.converted && !isConverting) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("${task.tsCount} 个分片 · ${formatSize(task.totalSize)}")
                    if (task.encrypted) append(" · AES-128")
                    if (task.converted) append(" · 已转换")
                },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }

        if (isConverting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (task.converted) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "已转换",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(22.dp))
        }
    }
}

/** 是否已有"所有文件访问权限" */
private fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

/** 跳转系统"所有文件访问权限"设置页 */
private fun requestAllFilesAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
    ).apply {
        data = android.net.Uri.parse("package:${context.packageName}")
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Log.e(TAG, "跳转权限设置页失败", it) }
}

internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return if (idx == 0) "$bytes B" else "%.2f %s".format(value, units[idx])
}
