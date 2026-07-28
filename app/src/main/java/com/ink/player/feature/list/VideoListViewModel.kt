package com.ink.player.feature.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.player.data.model.VideoInfo
import com.ink.player.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoListViewModel @Inject constructor(
    private val repository: VideoRepository
) : ViewModel() {

    private val _videos = MutableStateFlow<List<VideoInfo>>(emptyList())
    val videos: StateFlow<List<VideoInfo>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _videos.value = repository.scanVideos()
            _isLoading.value = false
        }
    }
}
