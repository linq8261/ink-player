package com.ink.player.feature.privacy

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 简单哈希函数，将数字密码转为 Long（用于本地比对） */
fun hashPassword(password: String): Long {
    var hash = 0L
    for (c in password) {
        hash = hash * 31 + c.code
    }
    return hash
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordScreen(
    title: String,
    subtitle: String = "",
    hint: String = "",
    isSetMode: Boolean = false,
    expectedHash: Long? = null,
    onBackClick: () -> Unit = {},
    onSuccess: (password: String) -> Unit = {},  // 传出密码明文，用于 AES 密钥派生
    onPasswordSet: ((Long) -> Unit)? = null
) {
    var input by remember { mutableStateOf("") }
    var confirmInput by remember { mutableStateOf("") }
    var isConfirmStage by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isShaking by remember { mutableStateOf(false) }

    val actualSubtitle = when {
        errorMessage != null -> errorMessage!!
        isConfirmStage -> "请再次输入密码确认"
        else -> subtitle
    }

    // 输入满6位后自动处理
    LaunchedEffect(input) {
        if (input.length < 6) return@LaunchedEffect

        if (isSetMode) {
            if (!isConfirmStage) {
                confirmInput = input
                input = ""
                isConfirmStage = true
            } else {
                if (input == confirmInput) {
                    val hash = hashPassword(input)
                    onPasswordSet?.invoke(hash)
                    onSuccess(input)
                } else {
                    errorMessage = "两次密码不一致，请重新设置"
                    input = ""
                    confirmInput = ""
                    isConfirmStage = false
                    isShaking = true
                }
            }
        } else {
            if (expectedHash != null && hashPassword(input) == expectedHash) {
                onSuccess(input)
            } else {
                errorMessage = "密码错误，请重试"
                input = ""
                isShaking = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (isConfirmStage) {
                            isConfirmStage = false
                            input = ""
                            confirmInput = ""
                            errorMessage = null
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF10101C))
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = actualSubtitle,
                color = if (errorMessage != null) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            if (isSetMode && hint.isNotBlank() && !isConfirmStage && errorMessage == null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = hint,
                    color = Color(0xFFFFB74D).copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            PasswordDots(
                length = input.length,
                maxLength = 6,
                isShaking = isShaking
            )

            Spacer(modifier = Modifier.height(40.dp))

            NumberPad(
                onNumberClick = { num ->
                    if (input.length < 6) {
                        input += num.toString()
                        errorMessage = null
                    }
                },
                onDelete = {
                    if (input.isNotEmpty()) {
                        input = input.dropLast(1)
                        errorMessage = null
                    }
                }
            )
        }
    }

    LaunchedEffect(isShaking) {
        if (isShaking) {
            delay(600)
            isShaking = false
        }
    }
}

private fun performHapticFeedback(context: android.content.Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
}

@Composable
private fun PasswordDots(
    length: Int,
    maxLength: Int,
    isShaking: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shake")
    val shakeOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(50),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeOffset"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (isShaking) Modifier.padding(horizontal = (shakeOffset * 8).dp) else Modifier
    ) {
        for (i in 0 until maxLength) {
            val isFilled = i < length
            val color by animateColorAsState(
                targetValue = when {
                    isShaking -> Color(0xFFFF6B6B)
                    isFilled -> Color.White
                    else -> Color.White.copy(alpha = 0.25f)
                },
                animationSpec = tween(200),
                label = "dotColor"
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = 1.dp,
                        color = if (isFilled || isShaking) Color.Transparent else Color.White.copy(alpha = 0.15f),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun NumberPad(
    onNumberClick: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val numbers = listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
        listOf(-1, 0, -2)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        numbers.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { num ->
                    when (num) {
                        -1 -> Spacer(modifier = Modifier.size(72.dp))
                        -2 -> DeleteKey(onClick = onDelete)
                        else -> NumberKey(num = num, onClick = { onNumberClick(num) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberKey(
    num: Int,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var isPressed by remember { mutableStateOf(false) }
    val bgAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.2f else 0.06f,
        animationSpec = tween(100),
        label = "keyBg"
    )

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = bgAlpha))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        performHapticFeedback(context)
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = num.toString(),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeleteKey(onClick: () -> Unit) {
    val context = LocalContext.current
    var isPressed by remember { mutableStateOf(false) }
    val bgAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.2f else 0.06f,
        animationSpec = tween(100),
        label = "delBg"
    )

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = bgAlpha))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        performHapticFeedback(context)
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "删除",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp
        )
    }
}
