package com.ink.player.data.repository

import com.ink.player.BuildConfig
import com.ink.player.data.remote.api.LeafPlayerApi
import com.ink.player.data.remote.dto.ApiResponse
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckUpdateRepository @Inject constructor(
    private val api: LeafPlayerApi
) {
    suspend fun checkForUpdate(): UpdateCheckResult {
        return try {
            val response = api.getLatestVersion()

            when {
                response.isSuccess && response.data != null -> {
                    val latestVersion = response.data
                    if (latestVersion.versionCode > BuildConfig.VERSION_CODE) {
                        UpdateCheckResult.HasUpdate(latestVersion)
                    } else {
                        UpdateCheckResult.NoUpdate
                    }
                }

                response.code == ApiResponse.CODE_SUCCESS -> UpdateCheckResult.NoUpdate
                else -> UpdateCheckResult.Error(response.message.ifBlank { "检查更新失败" })
            }
        } catch (e: HttpException) {
            // 服务端无该应用的版本记录时返回 404（业务码 1006），视为已是最新
            if (e.code() == 404) UpdateCheckResult.NoUpdate
            else UpdateCheckResult.Error("检查更新失败（HTTP ${e.code()}）")
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "检查更新失败")
        }
    }
}
