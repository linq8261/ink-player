package com.ink.player.data.remote.api

import com.ink.player.data.remote.dto.ApiResponse
import com.ink.player.data.remote.dto.VersionDto
import retrofit2.http.GET
import retrofit2.http.Path

interface LeafPlayerApi {

    /** 公开接口：GET /api/v1/apps/{packageName}/versions/latest */
    @GET("api/v1/apps/{packageName}/versions/latest")
    suspend fun getLatestVersion(
        @Path("packageName") packageName: String = "com.ink.player"
    ): ApiResponse<VersionDto>
}
