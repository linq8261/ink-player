package com.ink.player.feature.wukong

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "WUKONG_SCAN"

/**
 * 扫描悟空浏览器下载目录，识别「m3u8 + _ts 目录」结构的视频任务，
 * 并检查公共媒体库中是否已存在同名 mp4（已转过则置灰）。
 */
object WukongScanner {

    fun scan(context: Context): Result<List<VideoTask>> {
        val src = wukongSourceDir()
            ?: return Result.failure(
                IllegalStateException("无法访问悟空浏览器下载目录（需要所有文件访问权限）")
            )

        Log.d(TAG, "扫描源目录: ${src.displayPath()}")

        val entries = src.listFiles()
            ?: return Result.failure(IllegalStateException("读取目录失败: ${src.displayPath()}"))

        val tsDirs = entries.filter { it.isDirectory && it.name.endsWith("_ts") }
        Log.d(TAG, "发现 ${tsDirs.size} 个 _ts 目录")

        val tasks = tsDirs.map { buildTask(it, context) }

        Log.d(TAG, "扫描完成，共 ${tasks.size} 个任务")
        return Result.success(tasks)
    }

    private fun buildTask(tsDir: File, context: Context): VideoTask {
        val name = tsDir.name.removeSuffix("_ts")

        // ts 必须按数值序排列，字典序会错（10.ts < 2.ts）
        val tsFiles = (tsDir.listFiles()?.filter { it.isFile && it.extension == "ts" } ?: emptyList())
            .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
        val tsCount = tsFiles.size
        val totalSize = tsFiles.sumOf { it.length() }

        // MPEG-TS 同步字节为 0x47，首字节不是 0x47 即 AES 密文
        val encrypted = tsFiles.firstOrNull()?.let { f ->
            try {
                f.inputStream().use { it.read() != 0x47 }
            } catch (e: Exception) {
                Log.w(TAG, "读取 ${f.name} 失败", e)
                true
            }
        } ?: false

        val keyFile = File(tsDir, "enc.key").takeIf { it.isFile }

        // ts 目录内的本地化 m3u8 副本（排除 ts 与 enc.key）
        val localM3u8 = tsDir.listFiles()?.firstOrNull {
            it.isFile && it.extension != "ts" && it.name != "enc.key"
        }
        val info = localM3u8?.let { parseM3u8(it) }

        // 检查是否已在公共媒体库生成过同名 mp4
        val converted = isConverted(context, name)

        Log.d(
            TAG,
            "$name: ts=$tsCount size=$totalSize 加密=$encrypted key=${keyFile != null} " +
                "播放列表=${info?.segCount ?: 0} 已转换=$converted"
        )
        if (info != null && info.segCount != tsCount) {
            Log.w(TAG, "$name: 播放列表分片数(${info.segCount}) 与 ts 数($tsCount) 不一致")
        }

        return VideoTask(
            name = name,
            tsDirPath = tsDir.absolutePath,
            tsCount = tsCount,
            totalSize = totalSize,
            encrypted = encrypted,
            keyFilePath = keyFile?.absolutePath,
            playlistSegCount = info?.segCount ?: 0,
            iv = info?.iv,
            converted = converted,
            outputPath = null
        )
    }

    private fun parseM3u8(f: File): M3u8Info {
        var segCount = 0
        var iv: String? = null
        try {
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val s = line.trim()
                    when {
                        s.startsWith("#EXTINF") -> segCount++
                        s.startsWith("#EXT-X-KEY") -> {
                            iv = Regex("""IV=0x([0-9a-fA-F]+)""").find(s)?.groupValues?.get(1)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 m3u8 失败: ${f.absolutePath}", e)
        }
        return M3u8Info(segCount, iv)
    }

    private data class M3u8Info(val segCount: Int, val iv: String?)
}
