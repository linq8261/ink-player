package com.ink.player.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 服务端统一响应体：code 为业务码（0 表示成功，其余为错误码）。
 */
data class ApiResponse<T>(
    @SerializedName("code")
    val code: Int,
    @SerializedName("message")
    val message: String,
    @SerializedName("data")
    val data: T?,
    @SerializedName("requestId")
    val requestId: String? = null
) {
    val isSuccess: Boolean get() = code == CODE_SUCCESS

    companion object {
        const val CODE_SUCCESS = 0
    }
}
