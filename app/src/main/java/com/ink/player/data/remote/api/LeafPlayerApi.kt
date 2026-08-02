package com.ink.player.data.remote.api

import com.ink.player.data.remote.dto.ApiResponse
import com.ink.player.data.remote.dto.VersionDto
import retrofit2.http.GET
import retrofit2.http.Query

interface LeafPlayerApi {

    @GET("api/version/latest")
    suspend fun getLatestVersion(
        @Query("packageName") packageName: String = "com.ink.player"
    ): ApiResponse<VersionDto>
}
