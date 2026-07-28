package com.ink.player.data.remote.api

import com.ink.player.data.remote.dto.ApiResponse
import com.ink.player.data.remote.dto.VersionDto
import retrofit2.http.GET
import retrofit2.http.Query

interface LeafPlayerApi {

    @GET("api/version/latest")
    suspend fun getLatestVersion(
        @Query("appName") appName: String = "leafplayer",
        @Query("platform") platform: String = "android"
    ): ApiResponse<VersionDto>
}
