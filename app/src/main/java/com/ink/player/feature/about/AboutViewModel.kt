package com.ink.player.feature.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.player.data.remote.dto.VersionDto
import com.ink.player.data.repository.CheckUpdateRepository
import com.ink.player.data.repository.UpdateCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AboutUiState(
    val isChecking: Boolean = false,
    val updateResult: UpdateCheckResult? = null,
    val showUpdateDialog: Boolean = false,
    val updateVersion: VersionDto? = null
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val checkUpdateRepository: CheckUpdateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChecking = true, updateResult = null)
            val result = checkUpdateRepository.checkForUpdate()
            _uiState.value = _uiState.value.copy(isChecking = false, updateResult = result)

            when (result) {
                is UpdateCheckResult.HasUpdate -> {
                    _uiState.value = _uiState.value.copy(
                        showUpdateDialog = true,
                        updateVersion = result.versionInfo
                    )
                }
                else -> { /* 结果由 AboutScreen 处理 */ }
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.value = _uiState.value.copy(showUpdateDialog = false, updateResult = null)
    }

    fun clearUpdateResult() {
        _uiState.value = _uiState.value.copy(updateResult = null)
    }
}
