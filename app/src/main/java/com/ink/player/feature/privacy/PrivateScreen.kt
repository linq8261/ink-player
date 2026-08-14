package com.ink.player.feature.privacy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ink.player.ui.theme.VideoListAccent
import com.ink.player.ui.theme.VideoListBackground
import com.ink.player.ui.theme.VideoListCardPlaceholder
import com.ink.player.ui.theme.VideoListGlowBlue
import com.ink.player.ui.theme.VideoListGlowPurple
import com.ink.player.ui.theme.VideoListNavBar
import com.ink.player.ui.theme.VideoListTitleGradient
import androidx.compose.ui.graphics.asImageBitmap
import com.ink.player.util.VideoThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrivateScreen(
    onBackClick: () -> Unit = {},
    decryptPassword: String? = null, // 解密缩略图用的密码
    viewModel: PrivateViewModel = hiltViewModel()
) {
    val files by viewModel.files.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isDecrypting by viewModel.isDecrypting.collectAsState()
    val decryptingCount by viewModel.decryptingCount.collectAsState()
    val decryptedCount by viewModel.decryptedCount.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadFiles()
    }

    PrivateScreenContent(
        files = files,
        isLoading = isLoading,
        isSelectionMode = isSelectionMode,
        selectedIds = selectedIds,
        decryptPassword = decryptPassword,
        onBackClick = onBackClick,
        onEnterSelectionMode = { viewModel.enterSelectionMode(it) },
        onToggleSelection = { viewModel.toggleSelection(it) },
        onSelectAll = { viewModel.selectAll(it) },
        onDeselectAll = { viewModel.deselectAll() },
        onClearSelection = { viewModel.clearSelection() },
        onDecrypt = {
            if (decryptPassword != null) {
                viewModel.decryptSelectedFiles(decryptPassword)
            }
        }
    )

    // 取消加密进度对话框
    if (isDecrypting) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { /* 不可关闭 */ },
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
                        text = "正在取消加密...",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$decryptedCount / $decryptingCount",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrivateScreenContent(
    files: List<PrivateFileInfo>,
    isLoading: Boolean,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    decryptPassword: String? = null,
    onBackClick: () -> Unit,
    onEnterSelectionMode: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onDeselectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDecrypt: () -> Unit
) {
    val allIds = remember(files) { files.map { it.filePath } }
    val isAllSelected = allIds.isNotEmpty() && selectedIds.containsAll(allIds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VideoListBackground)
    ) {
        // 背景光晕
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
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    isAllSelected = isAllSelected,
                    onToggleAll = {
                        if (isAllSelected) onDeselectAll() else onSelectAll(allIds)
                    },
                    onCancel = onClearSelection
                )
            } else {
                PrivateHeader(fileCount = files.size, onBackClick = onBackClick)
            }

            when {
                isLoading -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VideoListAccent)
                }

                files.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无加密文件",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> PrivateFileGrid(
                    files = files,
                    isSelectionMode = isSelectionMode,
                    selectedIds = selectedIds,
                    decryptPassword = decryptPassword,
                    onEnterSelectionMode = onEnterSelectionMode,
                    onToggleSelection = onToggleSelection,
                    modifier = Modifier.weight(1f)
                )
            }

            if (isSelectionMode) {
                PrivateSelectionBottomBar(
                    selectedCount = selectedIds.size,
                    onDecrypt = onDecrypt
                )
            }
        }
    }
}

@Composable
private fun PrivateHeader(fileCount: Int, onBackClick: () -> Unit) {
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "加密文件夹",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    brush = Brush.linearGradient(VideoListTitleGradient)
                )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "PRIVATE · 共 $fileCount 个文件",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 56.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrivateFileGrid(
    files: List<PrivateFileInfo>,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    decryptPassword: String? = null,
    onEnterSelectionMode: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        items(files, key = { it.filePath }) { file ->
            PrivateFileGridItem(
                file = file,
                isSelected = file.filePath in selectedIds,
                isSelectionMode = isSelectionMode,
                decryptPassword = decryptPassword,
                onLongClick = { onEnterSelectionMode(file.filePath) },
                onToggleSelect = { onToggleSelection(file.filePath) },
                isScrolling = isScrolling
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrivateFileGridItem(
    file: PrivateFileInfo,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    decryptPassword: String? = null,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    isScrolling: Boolean
) {
    val context = LocalContext.current
    var thumbnail by remember(file.filePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.filePath, isScrolling) {
        if (!isScrolling) {
            thumbnail = if (decryptPassword != null && file.thumbPath != null) {
                // 从加密缩略图解密加载
                loadThumbFromEncrypted(file.thumbPath, decryptPassword)
            } else {
                // 回退：直接加载（非加密或无缩略图）
                VideoThumbnailLoader.load(context, android.net.Uri.fromFile(java.io.File(file.filePath)))
            }
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
                    if (isSelectionMode) onToggleSelect()
                },
                onLongClick = {
                    if (!isSelectionMode) onLongClick()
                }
            )
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        if (isSelected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(VideoListAccent.copy(alpha = 0.25f))
            )
        }

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

        // 文件名
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = file.fileName,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                text = file.formattedSize,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

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
private fun PrivateSelectionBottomBar(
    selectedCount: Int,
    onDecrypt: () -> Unit
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
            onClick = onDecrypt,
            enabled = selectedCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = VideoListAccent,
                disabledContainerColor = VideoListAccent.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LockOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "取消加密",
                fontSize = 14.sp
            )
        }
    }
}

/**
 * 从加密缩略图文件解密并加载到内存
 * 缩略图文件很小（几十 KB），全文件加解密无性能问题
 */
private suspend fun loadThumbFromEncrypted(
    thumbPath: String,
    password: String
): ImageBitmap? {
    return withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val thumbFile = java.io.File(thumbPath)
            if (!thumbFile.exists()) return@withContext null

            val decrypted = com.ink.player.util.FileEncryptionHelper.decryptThumbnail(thumbFile, password)
                ?: return@withContext null

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
            bitmap?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}