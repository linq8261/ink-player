package com.ink.player.data.repository

import com.ink.player.BuildConfig
import com.ink.player.data.remote.api.LeafPlayerApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckUpdateRepository @Inject constructor(
    private val api: LeafPlayerApi
) {
    suspend fun checkForUpdate(): UpdateCheckResult {
        return try {
            val response = api.getLatestVersion()

            if (response.code == "SUCCESS" && response.data != null) {
                val latestVersion = response.data
                val currentVersionCode = BuildConfig.VERSION_CODE

                if (latestVersion.versionCode > currentVersionCode) {
                    UpdateCheckResult.HasUpdate(latestVersion)
                } else {
                    UpdateCheckResult.NoUpdate
                }
            } else {
                UpdateCheckResult.Error(response.message)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "检查更新失败")
        }
    }
}
