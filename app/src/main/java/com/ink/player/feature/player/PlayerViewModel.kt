package com.ink.player.feature.player

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ink.player.data.local.dao.VideoProgressDao
import com.ink.player.data.local.entity.VideoProgressEntity
import com.ink.player.util.LogUtil
import com.ink.player.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application,
    private val progressDao: VideoProgressDao
) : ViewModel() {

    val player: ExoPlayer = ExoPlayer.Builder(application).build()
    private val appContext: Application = application

    private var videoUri: String? = null
    private var currentProgressKey: String? = null
    private var hasRestoredPosition = false

    /** 标记当前视频是否已播放完毕（播放完毕后点击屏幕从头开始播放） */
    private var playbackEnded = false

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _duration.value = player.duration.coerceAtLeast(0)
                    println("视频时长：${_duration.value}")
                    restorePosition()
                } else if (playbackState == Player.STATE_ENDED) {
                    // 播放完毕：自动停止；保留最近播放记录但进度清零（下次从头播放）
                    playbackEnded = true
                    player.pause()
                    val key = currentProgressKey
                    if (key != null) {
                        viewModelScope.launch {
                            progressDao.upsert(
                                VideoProgressEntity(
                                    videoUri = key,
                                    positionMs = 0,
                                    lastPlayedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    LogUtil.d("Playback ENDED, auto stop and progress cleared")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })

        // 从配置读取默认播放速度
        val prefs = com.ink.player.data.repository.UserPreferencesRepository.getInstance(application)
        viewModelScope.launch {
            prefs.normalSpeed.collect { speed ->
                normalSpeed = speed
                if (!player.isPlaying) {
                    player.setPlaybackSpeed(speed)
                    _playbackSpeed.value = speed
                }
            }
        }

        // Continuous position tracking while playing
        viewModelScope.launch {
            LogUtil.d("VM position polling STARTED")
            while (isActive) {
                val pos = player.currentPosition
                if (pos != _currentPosition.value) {
                    LogUtil.d("VM position UPDATE $pos")
                    _currentPosition.value = pos
                }
                delay(50)
            }
            LogUtil.d("VM position polling ENDED")
        }

        // Periodic progress save every 5 seconds
        viewModelScope.launch {
            while (isActive) {
                delay(5000)
                saveProgressNow()
            }
        }
    }

    fun play(videoUri: String) {
        this.videoUri = videoUri
        hasRestoredPosition = false
        playbackEnded = false
        currentProgressKey = null

        val mediaItem = MediaItem.fromUri(videoUri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        // Async resolve the normalized URI key, then restore if player is already ready
        viewModelScope.launch {
            val key = resolveMediaStoreKey(videoUri)
            currentProgressKey = key
            LogUtil.d("Progress key resolved: $key (original: $videoUri)")
            if (player.playbackState == Player.STATE_READY) {
                restorePosition()
            }
        }
    }

    // ── URI 归一化：尝试将任意 URI 转为标准的 MediaStore content URI ──

    private suspend fun resolveMediaStoreKey(uriString: String): String {
        val uri = Uri.parse(uriString)
        // 已经是 MediaStore 的 content URI，直接使用
        if (uri.scheme == "content" && MediaStore.AUTHORITY == uri.authority) {
            return uriString
        }
        return withContext(Dispatchers.IO) {
            try {
                val resolver = appContext.contentResolver
                val filePath = resolveFilePath(uri, resolver)
                if (filePath != null) {
                    val projection = arrayOf(MediaStore.Video.Media._ID)
                    val cursor = resolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.Video.Media.DATA} = ?",
                        arrayOf(filePath),
                        null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val id = it.getLong(0)
                            return@withContext "${MediaStore.Video.Media.EXTERNAL_CONTENT_URI}/$id"
                        }
                    }
                }
                // 查不到则回退到原始 URI
                uriString
            } catch (e: Exception) {
                LogUtil.d("URI resolve failed: ${e.message}")
                uriString
            }
        }
    }

    private fun resolveFilePath(uri: Uri, resolver: android.content.ContentResolver): String? {
        // file:// 直接取路径
        if (uri.scheme == "file") {
            return uri.path
        }
        // content:// 尝试从 _data 列取路径
        try {
            val projection = arrayOf(MediaStore.Video.Media.DATA)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    return cursor.getString(col)
                }
            }
        } catch (_: Exception) {}

        // DocumentsContract（文件管理器常用）
        try {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                if (uri.authority == "com.android.externalstorage.documents") {
                    val split = docId.split(":")
                    if (split.size >= 2) {
                        return "/storage/${split[0]}/${split[1]}"
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // ── 进度恢复 ──

    private fun restorePosition() {
        if (hasRestoredPosition) return
        val key = currentProgressKey ?: return
        hasRestoredPosition = true

        viewModelScope.launch {
            val saved = progressDao.getPosition(key)
            if (saved != null && saved > 0) {
                player.seekTo(saved)
                _currentPosition.value = saved
                LogUtil.d("Restored position: $saved ms")
            }
        }
    }

    // ── 进度保存 ──

    fun saveProgressNow() {
        if (playbackEnded) return // 播放完毕不保存结尾进度
        val key = currentProgressKey ?: return
        val pos = player.currentPosition
        if (pos > 0) {
            viewModelScope.launch {
                progressDao.upsert(
                    VideoProgressEntity(
                        videoUri = key,
                        positionMs = pos,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
                LogUtil.d("Saved progress: $pos ms")
            }
        }
    }

    // ── 播放控制 ──

    private var wasPlayingBeforeDrag = false

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPosition.value = positionMs
        // 拖拽/手势 seek 离开结尾后，解除“播放完毕”状态
        if (playbackEnded && positionMs < player.duration.coerceAtLeast(0)) {
            playbackEnded = false
        }
    }

    fun onTimelineDragStart() {
        wasPlayingBeforeDrag = player.isPlaying
        player.pause()
        LogUtil.d("Timeline drag START: wasPlaying=$wasPlayingBeforeDrag")
    }

    fun onTimelineDragEnd(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPosition.value = positionMs
        if (wasPlayingBeforeDrag) {
            player.play()
        }
        LogUtil.d("Timeline drag END: seekTo=$positionMs resume=$wasPlayingBeforeDrag")
    }

    fun seekBy(offsetMs: Long) {
        val newPos = (player.currentPosition + offsetMs).coerceAtMost(player.duration.coerceAtLeast(0))
        seekTo(newPos)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else if (player.playbackState == Player.STATE_ENDED) {
            // 播放完毕后再点击：从头开始播放
            playbackEnded = false
            player.seekTo(0)
            _currentPosition.value = 0
            player.play()
            LogUtil.d("Replay from start")
        } else {
            player.play()
        }
    }

    /** 默认播放速度（1倍速），后续可提供修改功能 */
    var normalSpeed = 1f

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    /** 按下时临时变速，松开后需调用 restoreSpeed() 恢复 */
    fun setTemporarySpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _playbackSpeed.value = speed
    }

    /** 恢复到 normalSpeed */
    fun restoreSpeed() {
        player.setPlaybackSpeed(normalSpeed)
        _playbackSpeed.value = normalSpeed
    }

    private val speedCycle = listOf(0.5f, 1f, 1.5f, 2f, 3f)

    /** 点击速度指示器轮流切换倍速 */
    fun cycleSpeed() {
        val current = normalSpeed
        val next = speedCycle.firstOrNull { it > current }
            ?: speedCycle.first()
        player.setPlaybackSpeed(next)
        _playbackSpeed.value = next
        normalSpeed = next
    }

    /** 截取当前帧并保存到相册（jpg） */
    fun captureFrame(context: Context) {
        val uri = videoUri ?: return
        val pos = player.currentPosition
        if (pos <= 0 || _duration.value <= 0) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(uri))
                val bitmap = retriever.getFrameAtTime(
                    pos * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                retriever.release()

                if (bitmap != null) {
                    saveBitmapToGallery(context, bitmap)
                    withContext(Dispatchers.Main) {
                        ToastUtil.show("截图已保存到相册")
                    }
                }
            } catch (e: Exception) {
                LogUtil.d("Capture frame failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    ToastUtil.show("截图失败: ${e.message}")
                }
            }
        }
    }

    private fun saveBitmapToGallery(context: Context, bitmap: android.graphics.Bitmap) {
        val filename = "InkPlayer_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG,
                    90,
                    outputStream
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, contentValues, null, null)
            }
        }
    }

    override fun onCleared() {
        // 同步保存，确保在 ViewModel 销毁前写入数据库（播放完毕则不保存）
        val key = currentProgressKey
        val pos = player.currentPosition
        if (!playbackEnded && key != null && pos > 0) {
            runBlocking {
                progressDao.upsert(
                    VideoProgressEntity(
                        videoUri = key,
                        positionMs = pos,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }
            LogUtil.d("Final save: $pos ms for $key")
        }
        super.onCleared()
        player.release()
    }
}
