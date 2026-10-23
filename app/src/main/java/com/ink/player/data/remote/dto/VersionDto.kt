package com.ink.player.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.ink.player.BuildConfig

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
    @SerializedName("fileSize")
    val fileSize: String?,
    /** 服务端返回的安装包下载地址，相对路径，如 /api/v1/versions/12/file；无上传文件时为 null */
    @SerializedName("fileUrl")
    val fileUrl: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("isForceUpdate")
    val isForceUpdate: Boolean,
    @SerializedName("createdAt")
    val createdAt: String?,
    @SerializedName("updatedAt")
    val updatedAt: String?
)

/**
 * 拼出可直接打开的下载地址：优先用后台填写的外链 url，
 * 否则用 fileUrl（相对路径）拼当前服务器 base。
 */
fun VersionDto.resolveDownloadUrl(): String? {
    url?.takeIf { it.isNotBlank() }?.let { return it }
    val path = fileUrl?.takeIf { it.isNotBlank() } ?: return null
    if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
        return path
    }
    return BuildConfig.SERVER_BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
}
