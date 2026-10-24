package com.ink.player.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ink.player.data.repository.UserPreferencesRepository
import com.ink.player.util.ToastUtil
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalScreen(
    onBackClick: () -> Unit = {},
    onNavigateToSetPassword: () -> Unit = {},
    onNavigateToVerifyPassword: () -> Unit = {},
    onNavigateToWukongConvert: () -> Unit = {}
) {
    val context = LocalContext.current
    val preferences = remember { UserPreferencesRepository.getInstance(context) }
    val passwordHash by preferences.privatePasswordHash.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实验性功能") },
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
                headlineContent = { Text("加密文件夹") },
                supportingContent = { Text("播放加密视频的时候很多问题，先解密到一个目录再播放太慢，直接在源文件解密存在播放中断的问题") },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    // 检查存储权限（Android 11+）
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                        && !android.os.Environment.isExternalStorageManager()
                    ) {
                        val intent =
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                .apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                        context.startActivity(intent)
                        ToastUtil.show("请先授予\"所有文件访问权限\"")
                    } else if (passwordHash != null) {
                        onNavigateToVerifyPassword()
                    } else {
                        onNavigateToSetPassword()
                    }
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("悟空浏览器下载视频转mp4") },
                supportingContent = { Text("发现ts切片分辨率等参数不一致，必须重编码，重编码太耗时了，暂时放弃这个功能") },
                leadingContent = { Icon(Icons.Default.Science, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onNavigateToWukongConvert)
            )
        }
    }
}
