package com.ink.player.feature.wukong

/**
 * 一个可转换的视频任务：m3u8 播放列表 + 同名 _ts 分片目录。
 */
data class VideoTask(
    /** 视频名（不含扩展名） */
    val name: String,
    /** 分片目录绝对路径 */
    val tsDirPath: String,
    /** ts 分片数量 */
    val tsCount: Int,
    /** 分片总大小（字节） */
    val totalSize: Long,
    /** 是否 AES-128 加密 */
    val encrypted: Boolean,
    /** enc.key 绝对路径，未加密为 null */
    val keyFilePath: String?,
    /** m3u8 播放列表中的分片数（应与 tsCount 一致，不一致说明下载不完整） */
    val playlistSegCount: Int,
    /** 解密 IV（十六进制字符串） */
    val iv: String?,
    /** 输出 mp4 是否已存在（已转换过） */
    val converted: Boolean = false,
    /** 输出 mp4 绝对路径 */
    val outputPath: String? = null
)
