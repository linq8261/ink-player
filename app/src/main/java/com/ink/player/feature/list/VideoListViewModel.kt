package com.ink.player.feature.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ink.player.data.local.dao.VideoProgressDao
import com.ink.player.data.model.VideoInfo
import com.ink.player.data.repository.VideoRepository
import com.ink.player.util.FileEncryptionHelper
import com.ink.player.util.LogUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoListViewModel @Inject constructor(
    application: Application,
    private val repository: VideoRepository,
    private val progressDao: VideoProgressDao
) : AndroidViewModel(application) {

    private val _videos = MutableStateFlow<List<VideoInfo>>(emptyList())
    val videos: StateFlow<List<VideoInfo>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 最近播放：按最后播放时间倒序（DAO 已 LIMIT 50）；已删除的视频自动跳过 */
    val recentVideos: StateFlow<List<VideoInfo>> = combine(
        _videos,
        progressDao.getRecentPlayed()
    ) { videos, progressList ->
        val byUri = videos.associateBy { it.uri.toString() }
        progressList.mapNotNull { byUri[it.videoUri] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 多选模式状态 ──

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    fun enterSelectionMode(initialId: Long) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(initialId)
    }

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun selectAll(ids: List<Long>) {
        _selectedIds.value = ids.toSet()
    }

    fun clearSelection() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    // ── 加密进度状态 ──

    private val _encryptingCount = MutableStateFlow(0)
    val encryptingCount: StateFlow<Int> = _encryptingCount.asStateFlow()

    private val _encryptedCount = MutableStateFlow(0)
    val encryptedCount: StateFlow<Int> = _encryptedCount.asStateFlow()

    fun getSelectedVideos(): List<VideoInfo> {
        val ids = _selectedIds.value
        return _videos.value.filter { it.id in ids }
    }

    fun encryptSelectedVideos(password: String) {
        val selected = getSelectedVideos()
        if (selected.isEmpty()) return

        _encryptingCount.value = selected.size
        _encryptedCount.value = 0

        viewModelScope.launch(Dispatchers.IO) {
            val targets = selected.map {
                FileEncryptionHelper.EncryptTarget(it.uri, it.id)
            }
            FileEncryptionHelper.encryptFiles(
                targets,
                getApplication(),
                password,
                onProgress = { current, total ->
                    _encryptingCount.value = total
                    _encryptedCount.value = current
                }
            )
            // 通知系统媒体库重新扫描，确保删除的文件立即从 MediaStore 中消失
            FileEncryptionHelper.notifyMediaScanner(getApplication())
            kotlinx.coroutines.delay(300) // 等 MediaStore 刷新
            clearSelection()
            loadVideos()
            _encryptingCount.value = 0
        }
    }

    /**
     * 删除选中的视频：从 MediaStore 删除文件并清理播放进度记录
     */
    fun deleteSelectedVideos() {
        val selected = getSelectedVideos()
        if (selected.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            for (video in selected) {
                try {
                    if (resolver.delete(video.uri, null, null) > 0) {
                        progressDao.deleteByUri(video.uri.toString())
                    }
                } catch (e: Exception) {
                    LogUtil.d("删除视频失败: ${video.displayName}, ${e.message}")
                }
            }
            // 通知系统媒体库刷新，确保删除立即生效
            FileEncryptionHelper.notifyMediaScanner(getApplication())
            kotlinx.coroutines.delay(300) // 等 MediaStore 刷新
            clearSelection()
            loadVideos()
        }
    }

    fun loadVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _videos.value = repository.scanVideos()
            _isLoading.value = false
        }
    }

    fun refreshVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            _videos.value = repository.scanVideos()
            _isRefreshing.value = false
        }
    }
}
