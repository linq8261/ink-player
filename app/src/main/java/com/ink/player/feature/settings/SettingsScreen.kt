package com.ink.player.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ink.player.data.repository.UserPreferencesRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToExperimental: () -> Unit = {}
) {
    val context = LocalContext.current
    val preferences = remember { UserPreferencesRepository.getInstance(context) }
    val seekThrottleMs by preferences.seekThrottleMs.collectAsState(initial = 100L)
    val normalSpeed by preferences.normalSpeed.collectAsState(initial = 1f)
    val gestureBrightness by preferences.gestureBrightnessEnabled.collectAsState(initial = false)
    val gestureVolume by preferences.gestureVolumeEnabled.collectAsState(initial = false)
    var showThrottleDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(top = 8.dp)
        ) {
            ListItem(
                headlineContent = { Text("拖动响应间隔") },
                supportingContent = { Text("${seekThrottleMs}ms") },
                leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { showThrottleDialog = true }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("默认播放速度") },
                supportingContent = { Text("${normalSpeed}x") },
                leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { showSpeedDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            SettingsSwitchItem(
                icon = Icons.Default.Star,
                title = "亮度滑动调节",
                checked = gestureBrightness,
                onCheckedChange = { scope.launch { preferences.setGestureBrightnessEnabled(it) } }
            )

            SettingsSwitchItem(
                icon = Icons.Default.Notifications,
                title = "音量滑动调节",
                checked = gestureVolume,
                onCheckedChange = { scope.launch { preferences.setGestureVolumeEnabled(it) } }
            )

            ListItem(
                headlineContent = { Text("实验性功能") },
                leadingContent = { Icon(Icons.Default.Science, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onNavigateToExperimental)
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("关于") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onNavigateToAbout)
            )
        }
    }

    if (showThrottleDialog) {
        SeekThrottleDialog(
            currentValue = seekThrottleMs,
            onConfirm = { newValue ->
                scope.launch { preferences.setSeekThrottleMs(newValue) }
                showThrottleDialog = false
            },
            onDismiss = { showThrottleDialog = false }
        )
    }

    if (showSpeedDialog) {
        SpeedSelectDialog(
            currentSpeed = normalSpeed,
            onConfirm = { speed ->
                scope.launch { preferences.setNormalSpeed(speed) }
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false }
        )
    }
}

@Composable
private fun SeekThrottleDialog(
    currentValue: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("拖动响应间隔") },
        text = {
            Column {
                Text(
                    text = "设置拖动时间轴时 seek 操作的节流间隔。数值越大，拖动时 seek 频率越低，解码压力越小，但是画面会不连贯。数值越小，拖动画面越连贯，但是解码压力大，可能会卡。亲测100ms比较合理",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it.filter { c -> c.isDigit() } },
                    label = { Text("间隔 (ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = inputText.toLongOrNull()?.coerceIn(10L, 500L) ?: 100L
                onConfirm(value)
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private val speedOptions = listOf(0.5f, 1f, 1.5f, 2f, 3f)

@Composable
private fun SpeedSelectDialog(
    currentSpeed: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSpeed by remember { mutableStateOf(currentSpeed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("默认播放速度") },
        text = {
            Column {
                speedOptions.forEach { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSpeed = speed }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedSpeed == speed,
                            onClick = { selectedSpeed = speed }
                        )
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        Text(
                            text = "${speed}x",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedSpeed) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
internal fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.padding(start = 16.dp))

        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
