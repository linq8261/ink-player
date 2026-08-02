package com.ink.player.data.remote.dto

import com.google.gson.annotations.SerializedName

data class VersionDto(
    val id: Int,
    @SerializedName("packageName")
    val packageName: String,
    @SerializedName("versionCode")
    val versionCode: Int,
    @SerializedName("versionName")
    val versionName: String,
    @SerializedName("updateTitle")
    val updateTitle: String?,
    @SerializedName("updateContent")
    val updateContent: String?,
    @SerializedName("filePath")
    val filePath: String?,
    @SerializedName("fileSize")
    val fileSize: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("isForceUpdate")
    val isForceUpdate: Boolean,
    @SerializedName("createdAt")
    val createdAt: String?,
    @SerializedName("updatedAt")
    val updatedAt: String?
)
