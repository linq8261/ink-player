package com.ink.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ink.player.data.remote.dto.VersionDto
import com.ink.player.data.repository.UpdateCheckResult
import com.ink.player.feature.list.VideoListScreen
import com.ink.player.feature.player.PlayerScreen
import com.ink.player.feature.about.AboutScreen
import com.ink.player.feature.settings.SettingsScreen
import com.ink.player.navigation.NavRoutes
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    @Inject
    lateinit var checkUpdateRepository: com.ink.player.data.repository.CheckUpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _root_ide_package_.com.ink.player.ui.theme.InkPlayerTheme {
                val nc = rememberNavController()
                navController = nc

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
                        VideoListScreen(
                            onVideoClick = { uri ->
                                nc.navigate(NavRoutes.playerRoute(uri))
                            },
                            onNavigateToSettings = {
                                nc.navigate(NavRoutes.SETTINGS)
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
                }

                // 自动检查更新弹窗（覆盖在所有页面之上）
                pendingUpdateInfo?.let { versionInfo ->
                    UpdateDialog(
                        versionInfo = versionInfo,
                        onDismiss = { pendingUpdateInfo = null },
                        onUpdate = {
                            pendingUpdateInfo = null
                            val downloadUrl = versionInfo.url ?: versionInfo.filePath
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
