package com.ink.player.feature.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.player.data.local.dao.VideoProgressDao
import com.ink.player.data.model.VideoInfo
import com.ink.player.data.repository.VideoRepository
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
    private val repository: VideoRepository,
    progressDao: VideoProgressDao
) : ViewModel() {

    private val _videos = MutableStateFlow<List<VideoInfo>>(emptyList())
    val videos: StateFlow<List<VideoInfo>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 最近播放：按最后播放时间倒序（DAO 已 LIMIT 50）；已删除的视频自动跳过 */
    val recentVideos: StateFlow<List<VideoInfo>> = combine(
        _videos,
        progressDao.getRecentPlayed()
    ) { videos, progressList ->
        val byUri = videos.associateBy { it.uri.toString() }
        progressList.mapNotNull { byUri[it.videoUri] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _videos.value = repository.scanVideos()
            _isLoading.value = false
        }
    }
}
