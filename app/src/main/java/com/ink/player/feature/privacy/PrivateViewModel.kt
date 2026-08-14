package com.ink.player.feature.privacy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ink.player.util.FileEncryptionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrivateViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val _files = MutableStateFlow<List<PrivateFileInfo>>(emptyList())
    val files: StateFlow<List<PrivateFileInfo>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── 多选模式状态 ──

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun enterSelectionMode(initialId: String) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(initialId)
    }

    fun toggleSelection(id: String) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
    }

    fun selectAll(ids: List<String>) {
        _selectedIds.value = ids.toSet()
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun clearSelection() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _files.value = scanPrivateFiles()
            _isLoading.value = false
        }
    }

    // ── 取消加密状态 ──

    private val _isDecrypting = MutableStateFlow(false)
    val isDecrypting: StateFlow<Boolean> = _isDecrypting.asStateFlow()

    private val _decryptingCount = MutableStateFlow(0)
    val decryptingCount: StateFlow<Int> = _decryptingCount.asStateFlow()

    private val _decryptedCount = MutableStateFlow(0)
    val decryptedCount: StateFlow<Int> = _decryptedCount.asStateFlow()

    fun decryptSelectedFiles(password: String) {
        val selected = _selectedIds.value.toList()
        if (selected.isEmpty()) return

        _isDecrypting.value = true
        _decryptingCount.value = selected.size
        _decryptedCount.value = 0

        viewModelScope.launch(Dispatchers.IO) {
            FileEncryptionHelper.decryptFiles(
                selected,
                getApplication(),
                password,
                onProgress = { current, total ->
                    _decryptingCount.value = total
                    _decryptedCount.value = current
                }
            )
            clearSelection()
            loadFiles()
            _isDecrypting.value = false
        }
    }

    private fun scanPrivateFiles(): List<PrivateFileInfo> {
        val dir = FileEncryptionHelper.getPrivateDir(getApplication())
        if (!dir.exists()) return emptyList()

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", java.util.Locale.getDefault())
        return dir.listFiles()
            ?.filter { it.extension.equals("priv", ignoreCase = true) }
            ?.map { file ->
                val encryptTime = try {
                    dateFormat.parse(file.nameWithoutExtension)?.time ?: file.lastModified()
                } catch (e: Exception) {
                    file.lastModified()
                }
                val thumbFile = File(file.parent, file.nameWithoutExtension + FileEncryptionHelper.THUMB_SUFFIX)
                PrivateFileInfo(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    encryptTime = encryptTime,
                    thumbPath = if (thumbFile.exists()) thumbFile.absolutePath else null
                )
            }
            ?: emptyList()
    }
}
