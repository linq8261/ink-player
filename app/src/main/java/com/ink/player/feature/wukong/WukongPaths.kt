package com.ink.player.feature.wukong

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

private const val TAG = "WUKONG_PATH"

/** 零宽空格 U+200B。插在 "Android/" 之后可绕过系统的 data 目录访问限制 */
const val ZWSP = "\u200B"

/** 悟空浏览器 m3u8 下载根目录（相对 /sdcard） */
private const val WUKONG_SRC_REL = "Android/data/com.cat.readall/files/Download/m3u8"

/** 输出目录名（位于公共 Download 下） */
const val OUTPUT_DIR_NAME = "wukong"

/**
 * 悟空浏览器源目录。
 *
 * 注意：该目录位于 Android/data 下，直接访问会被系统拒绝（即使有"所有文件访问权限"）。
 * 这里借助零宽字符 U+200B 绕过：framework 校验层看到的路径不匹配黑名单而放行，
 * 内核 case-fold 解析时忽略 U+200B，实际打开的仍是真实目录。
 *
 * @return 可直接读取的 File，若不可用返回 null
 */
fun wukongSourceDir(): File? {
    val root = Environment.getExternalStorageDirectory()
    val raw = File(root, WUKONG_SRC_REL)
    if (raw.isDirectory && raw.canRead()) {
        Log.d(TAG, "源目录可直接访问: ${raw.absolutePath}")
        return raw
    }
    // 在 "Android/" 之后插入零宽字符
    val bypass = File(root, "Android/$ZWSP" + "data/com.cat.readall/files/Download/m3u8")
    val ok = try {
        bypass.isDirectory && bypass.canRead()
    } catch (e: Exception) {
        Log.w(TAG, "零宽路径访问异常", e)
        false
    }
    Log.d(TAG, "源目录零宽路径: ${bypass.absolutePath} 可用=$ok")
    return if (ok) bypass else null
}

/**
 * 转换输出目录：app 专属外部目录（Android/data/<pkg>/files/Movies/wukong/）。
 *
 * 注意：不能直接写公共 Download/，Android 15+ 的 MediaProvider 会把未经 MediaStore
 * 声明的文件强制移入回收站（renameToTrash），转换中途文件被移走导致 writeSampleData 失败。
 * 转换完成后由 [publishToMediaStore] 发布到公共媒体库。
 */
fun outputDir(context: Context): File {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    return File(base, OUTPUT_DIR_NAME)
}

/**
 * 确保输出目录存在。
 */
fun ensureOutputDir(context: Context): File {
    val dir = outputDir(context)
    if (!dir.exists()) {
        val ok = dir.mkdirs()
        Log.d(TAG, "创建输出目录 ${dir.absolutePath} 结果=$ok")
    }
    return dir
}

/**
 * 判断指定视频是否已在公共媒体库 Movies/wukong 下生成过 mp4。
 */
fun isConverted(context: Context, name: String): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_MOVIES}/$OUTPUT_DIR_NAME/")
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        val target = "$name.mp4"
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == target) return true
            }
        }
        false
    } else {
        val f = File(outputDir(context), "$name.mp4")
        f.isFile && f.length() > 0
    }
}

/**
 * 把转换好的 mp4 发布到公共媒体库（Movies/wukong/），并删除私有目录中的临时文件。
 * API 29+ 用 MediaStore 拷贝（声明所有权，避免被系统回收）；API < 29 直接扫描私有路径。
 *
 * @return 是否发布成功
 */
fun publishToMediaStore(context: Context, file: File): Boolean {
    // API < 29 无法用 MediaStore.RELATIVE_PATH，直接扫描私有目录路径
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
        )
        return true
    }

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$OUTPUT_DIR_NAME")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: run {
            Log.w(TAG, "发布到媒体库失败：insert 返回 null")
            return false
        }
    return try {
        val os = resolver.openOutputStream(uri)
        if (os == null) {
            Log.w(TAG, "发布到媒体库失败：openOutputStream 返回 null")
            runCatching { resolver.delete(uri, null, null) }
            false
        } else {
            os.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            Log.d(TAG, "已发布到媒体库: Movies/$OUTPUT_DIR_NAME/${file.name}")
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "发布到媒体库失败", e)
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

/** 把路径里不可见的零宽字符显示出来，便于日志排查 */
fun File.displayPath(): String = absolutePath.replace(ZWSP, "[U+200B]")
