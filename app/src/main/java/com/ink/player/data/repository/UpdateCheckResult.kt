package com.ink.player.data.repository

import com.ink.player.data.remote.dto.VersionDto

sealed class UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult()
    data class HasUpdate(val versionInfo: VersionDto) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}
