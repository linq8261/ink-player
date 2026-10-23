package com.ink.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ink.player.data.remote.dto.VersionDto
import com.ink.player.data.remote.dto.resolveDownloadUrl
import com.ink.player.data.repository.CheckUpdateRepository
import com.ink.player.data.repository.UpdateCheckResult
import com.ink.player.data.repository.UserPreferencesRepository
import com.ink.player.feature.list.VideoListScreen
import com.ink.player.feature.player.PlayerScreen
import com.ink.player.feature.about.AboutScreen
import com.ink.player.feature.privacy.PasswordScreen
import com.ink.player.feature.privacy.PrivateScreen
import com.ink.player.feature.settings.ExperimentalScreen
import com.ink.player.feature.settings.SettingsScreen
import com.ink.player.feature.wukong.WukongConvertScreen
import com.ink.player.ui.theme.InkPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    @Inject
    lateinit var checkUpdateRepository: CheckUpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InkPlayerTheme {
                val nc = rememberNavController()
                navController = nc

                // 当前验证通过的密码（由 VERIFY_PASSWORD / SET_PASSWORD 页面设置，用于加密解密）
                var currentPassword by remember { mutableStateOf<String?>(null) }

                // App 退到后台或退出时清空密码，重新进入需重新验证
                DisposableEffect(Unit) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                            currentPassword = null
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                var pendingUpdateInfo by remember {
                    mutableStateOf<com.ink.player.data.remote.dto.VersionDto?>(
                        null
                    )
                }

                // 启动时自动检查更新
                LaunchedEffect(Unit) {
                    when (val result = checkUpdateRepository.checkForUpdate()) {
                        is UpdateCheckResult.HasUpdate -> {
                            pendingUpdateInfo = result.versionInfo
                        }

                        else -> { /* 静默处理 */
                        }
                    }
                }

                NavHost(
                    navController = nc,
                    startDestination = NavRoutes.VIDEO_LIST
                ) {
                    composable(NavRoutes.VIDEO_LIST) {
                        val preferences = remember { UserPreferencesRepository.getInstance(this@MainActivity) }
                        val passwordHash by preferences.privatePasswordHash.collectAsState(initial = null)

                        VideoListScreen(
                            externalEncryptPassword = currentPassword,
                            onPasswordConsumed = { /* 不清空，保持当前 Session 有效 */ },
                            onVideoClick = { uri ->
                                nc.navigate(NavRoutes.playerRoute(uri))
                            },
                            onNavigateToSettings = {
                                nc.navigate(NavRoutes.SETTINGS)
                            },
                            onEncryptClick = { selectedVideos ->
                                // 先检查存储权限（Android 11+）
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                                    && !android.os.Environment.isExternalStorageManager()
                                ) {
                                    requestManageStoragePermission()
                                    null
                                } else if (passwordHash != null) {
                                    if (currentPassword != null) {
                                        // 当前 Session 已验证过密码，直接返回密码
                                        currentPassword
                                    } else {
                                        // 跳转到密码验证页，验证成功后回传密码
                                        nc.navigate(NavRoutes.VERIFY_PASSWORD)
                                        null
                                    }
                                } else {
                                    nc.navigate(NavRoutes.SET_PASSWORD)
                                    null
                                }
                            }
                        )
                    }

                    composable(
                        route = NavRoutes.PLAYER,
                        arguments = listOf(
                            navArgument("videoUri") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val videoUri = backStackEntry.arguments?.getString("videoUri")
                        PlayerScreen(
                            videoUri = videoUri,
                            decryptPassword = currentPassword,
                            onBackClick = {
                                if (!nc.popBackStack()) {
                                    finish()
                                }
                            },
                            onNavigateToSettings = {
                                nc.navigate(NavRoutes.SETTINGS)
                            }
                        )
                    }

                    composable(NavRoutes.SETTINGS) {
                        SettingsScreen(
                            onBackClick = {
                                nc.popBackStack()
                            },
                            onNavigateToAbout = {
                                nc.navigate(NavRoutes.ABOUT)
                            },
                            onNavigateToExperimental = {
                                nc.navigate(NavRoutes.EXPERIMENTAL)
                            }
                        )
                    }

                    composable(NavRoutes.EXPERIMENTAL) {
                        ExperimentalScreen(
                            onBackClick = {
                                nc.popBackStack()
                            },
                            onNavigateToSetPassword = {
                                nc.navigate(NavRoutes.SET_PASSWORD)
                            },
                            onNavigateToVerifyPassword = {
                                // 如果当前 Session 已验证过密码，直接进入加密文件夹
                                if (currentPassword != null) {
                                    nc.navigate(NavRoutes.PRIVATE)
                                } else {
                                    nc.navigate(NavRoutes.VERIFY_PASSWORD)
                                }
                            },
                            onNavigateToWukongConvert = {
                                nc.navigate(NavRoutes.WUKONG_CONVERT)
                            }
                        )
                    }

                    composable(NavRoutes.WUKONG_CONVERT) {
                        WukongConvertScreen(
                            onBackClick = {
                                nc.popBackStack()
                            }
                        )
                    }

                    composable(NavRoutes.ABOUT) {
                        AboutScreen(
                            onBackClick = {
                                nc.popBackStack()
                            }
                        )
                    }

                    composable(NavRoutes.SET_PASSWORD) {
                        val preferences = remember { UserPreferencesRepository.getInstance(this@MainActivity) }
                        val scope = rememberCoroutineScope()
                        PasswordScreen(
                            title = "设置密码",
                            subtitle = "请设置6位数字密码",
                            hint = "密码用于保护加密文件，卸载 App 后密码信息将丢失，请务必记住密码！如之前已设置过密码，请设置一样的密码，否则之前加密的文件无法解密！",
                            isSetMode = true,
                            onBackClick = { nc.popBackStack() },
                            onSuccess = {
                                nc.popBackStack()
                                nc.navigate(NavRoutes.PRIVATE)
                            },
                            onPasswordSet = { hash ->
                                scope.launch {
                                    preferences.setPrivatePasswordHash(hash)
                                }
                            }
                        )
                    }

                    composable(NavRoutes.VERIFY_PASSWORD) {
                        val preferences = remember { UserPreferencesRepository.getInstance(this@MainActivity) }
                        val passwordHash by preferences.privatePasswordHash.collectAsState(initial = null)
                        PasswordScreen(
                            title = "验证密码",
                            subtitle = "请输入密码",
                            isSetMode = false,
                            expectedHash = passwordHash,
                            onBackClick = { nc.popBackStack() },
                            onSuccess = { password ->
                                currentPassword = password
                                // 判断来源：如果前一个页面是 EXPERIMENTAL，则进入加密文件夹；否则是加密操作
                                val prevRoute = nc.previousBackStackEntry?.destination?.route
                                if (prevRoute == NavRoutes.EXPERIMENTAL) {
                                    nc.popBackStack()
                                    nc.navigate(NavRoutes.PRIVATE)
                                } else {
                                    // 加密操作：回到视频列表，密码已存到 currentPassword
                                    nc.popBackStack()
                                }
                            }
                        )
                    }

                    composable(NavRoutes.PRIVATE) {
                        PrivateScreen(
                            decryptPassword = currentPassword,
                            onBackClick = { nc.popBackStack() }
                        )
                    }
                }

                // 自动检查更新弹窗（覆盖在所有页面之上）
                pendingUpdateInfo?.let { versionInfo ->
                    UpdateDialog(
                        versionInfo = versionInfo,
                        onDismiss = { pendingUpdateInfo = null },
                        onUpdate = {
                            pendingUpdateInfo = null
                            val downloadUrl = versionInfo.resolveDownloadUrl()
                            if (!downloadUrl.isNullOrBlank()) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    )
                }

                // 处理启动时的外部 Intent
                LaunchedEffect(Unit) {
                    handleIntent(intent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val videoUri = intent.data.toString()
            navController?.navigate(NavRoutes.playerRoute(videoUri)) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    /**
     * 请求"所有文件访问权限"（Android 11+），用于在 /sdcard/ 下创建加密文件夹
     * Android 10 及以下不需要此权限
     */
    private fun requestManageStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
}

@Composable
private fun UpdateDialog(
    versionInfo: VersionDto,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = versionInfo.updateTitle ?: "发现新版本",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Text(
                    text = "最新版本: ${versionInfo.versionName} (${versionInfo.versionCode})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                versionInfo.fileSize?.let {
                    Text(
                        text = "安装包大小: $it",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = versionInfo.updateContent ?: "修复了一些问题，提升用户体验",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onUpdate) {
                Text("立即更新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后再说")
            }
        }
    )
}
