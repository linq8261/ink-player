# 修复：加密时缩略图生成失败（源文件已被移动）

## 背景与问题

**现象**：加密后的视频在"加密文件夹"中不显示封面（显示深色占位）。

**adb 日志定位**（`[EncryptHelper]`，2026-08-14 20:49）：
```
→ renameTo 移动成功（同分区）
→ AES 加密文件头部...
[CryptoHelper] 加密文件头完成: 2026-08-14_204916_283.priv (16384 bytes, shift=28)
→ 生成加密缩略图...
→ 缩略图生成失败: /storage/emulated/0/Movies/MyHelper/cover_xxx.mp4 does not exist
→ 加密成功
```

**根因**：`FileEncryptionHelper.encryptFiles()` 中执行顺序错误：

| 步骤 | 当前行号 | 操作 | 问题 |
|------|---------|------|------|
| 4 | `FileEncryptionHelper.kt:77` | `sourceFile.renameTo(targetFile)` 移动文件到加密目录 | **文件已被移走，源路径立即失效** |
| 5 | `FileEncryptionHelper.kt:89` | `CryptoHelper.encryptHeader()` 加密头部 | 正常 |
| 6 | `FileEncryptionHelper.kt:93` | `saveEncryptedThumbnail(targetFile, sourcePath, ...)` 从源路径取首帧 | `sourcePath` 已不存在 → `setDataSource` 抛异常 → 未生成 `.thumb` |

代码注释写"从源文件获取，此时源文件尚未删除"，但文件在步骤 4 已被 **renameTo 移走**（不是未删除），导致 `MediaMetadataRetriever.setDataSource(sourcePath)` 抛 `does not exist`。

**连锁影响**：没有 `.thumb` 文件 → `PrivateScreen.kt:342` 回退直接解码 `.priv` 文件 → 头部已被 AES 加密、MMR 必然解析失败 → 永远显示占位。

## 修复方案

**核心思路**：在移动文件**之前**先从源文件提取缩略图字节，移动 + 加密头部之后再保存加密缩略图。

将原 `saveEncryptedThumbnail()`（`FileEncryptionHelper.kt:223-250`）拆分为两个职责单一的函数：

1. **`extractThumbnailBytes(sourcePath: String): ByteArray?`** — 从源文件取首帧 → 缩放 320x240 → JPEG 压缩 → 返回字节数组（`try/finally` 保证 `retriever.release()`）
2. **`saveEncryptedThumbnail(targetFile: File, thumbBytes: ByteArray, password: String)`** — 写 `.thumb` 文件 + `CryptoHelper.encryptFullFile()` 整体加密

### 调整后的 encryptFiles() 流程

```
1. 确保加密目录和 .nomedia 存在
2. 解析 content:// URI 为真实路径 sourcePath
3. 从 sourcePath 提取缩略图字节 thumbBytes        ← 提前到移动之前（源文件仍存在）
4. 移动文件到加密目录（renameTo / copyTo 回退）
5. AES-256-GCM 加密文件头部 16KB
6. thumbBytes 非空时保存加密缩略图 .thumb
7. 删除原文件 + 删除 MediaStore 记录
8. 报告进度
```

## 受影响文件

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/ink/player/util/FileEncryptionHelper.kt` | `encryptFiles()` 调用顺序调整（第 89-93 行区域）；`saveEncryptedThumbnail()`（第 223-250 行）拆分为 `extractThumbnailBytes()` + `saveEncryptedThumbnail()` |

无其他文件受影响（`PrivateScreen` / `PrivateViewModel` / `CryptoHelper` 均无需改动）。

## 代码改动

### 改动 1：`encryptFiles()` 调用顺序

```kotlin
try {
    // 4. 先从源文件提取缩略图（此时源文件尚未移动，路径有效）
    val thumbBytes = extractThumbnailBytes(sourcePath)

    // 5. 移动文件到加密目录（优先 renameTo 同分区原子操作，失败回退 copyTo）
    val moved = if (sourceFile.renameTo(targetFile)) {
        LogUtil.d("[$TAG]   → renameTo 移动成功（同分区）")
        true
    } else {
        LogUtil.d("[$TAG]   → renameTo 失败，改用 copyTo（跨分区）")
        sourceFile.copyTo(targetFile, overwrite = true).exists()
    }
    if (moved) {
        // 6. AES-256-GCM 加密文件头部 16KB
        LogUtil.d("[$TAG]   → AES 加密文件头部...")
        CryptoHelper.encryptHeader(targetFile, password)

        // 7. 保存加密缩略图（使用移动前提取的字节，避免源路径失效问题）
        LogUtil.d("[$TAG]   → 生成加密缩略图...")
        thumbBytes?.let { saveEncryptedThumbnail(targetFile, it, password) }

        // 8. 删除原文件
        LogUtil.d("[$TAG]   → 删除原文件")
        val deleted = sourceFile.delete()
        LogUtil.d("[$TAG]   → 原文件删除: $deleted")

        // 8. 从系统媒体库删除记录，防止相册/播放器残留
        deleteFromMediaStore(video.uri, video.id, context)
        LogUtil.d("[$TAG]   → MediaStore 记录已删除")

        successCount++
        LogUtil.d("[$TAG]   → 加密成功")
    } else {
        LogUtil.d("[$TAG]   → 加密失败: 复制后目标文件不存在")
    }
} catch (e: Exception) {
    LogUtil.d("[$TAG]   → 加密异常: ${e.message}")
}
```

### 改动 2：拆分 `saveEncryptedThumbnail()`

原函数（第 223-250 行）替换为：

```kotlin
/**
 * 从源视频提取首帧缩略图字节（必须在文件移动前调用）
 * @return JPEG 字节数组，提取失败返回 null
 */
private fun extractThumbnailBytes(sourcePath: String): ByteArray? {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(sourcePath)
        val bitmap = retriever.frameAtTime
            ?: run {
                LogUtil.d("[$TAG]   → 无法获取视频首帧")
                return null
            }
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 320, 240, true)
        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
        baos.toByteArray()
    } catch (e: Exception) {
        LogUtil.d("[$TAG]   → 缩略图生成失败: ${e.message}")
        null
    } finally {
        retriever.release()
    }
}

/**
 * 将缩略图字节加密后保存为 .thumb 文件
 */
private fun saveEncryptedThumbnail(targetFile: File, thumbBytes: ByteArray, password: String) {
    try {
        val thumbFile = File(targetFile.parent, targetFile.nameWithoutExtension + THUMB_SUFFIX)
        thumbFile.writeBytes(thumbBytes)
        CryptoHelper.encryptFullFile(thumbFile, password)
        LogUtil.d("[$TAG]   → 缩略图已加密保存: ${thumbFile.name} (${thumbBytes.size} bytes)")
    } catch (e: Exception) {
        LogUtil.d("[$TAG]   → 缩略图保存失败: ${e.message}")
    }
}
```

注：新实现用 `try/finally` 保证 `retriever.release()` 一定执行（原实现在取帧异常时不会 release，属于顺带修正）。

## 边界条件与异常处理

| 场景 | 处理 |
|------|------|
| `frameAtTime` 返回 null（无法取首帧） | 返回 null，不生成缩略图，加密主流程不受影响（与原行为一致） |
| 取帧/压缩抛异常 | 捕获并记日志，返回 null，不影响加密 |
| 写 `.thumb` / 加密缩略图抛异常 | 捕获并记日志，不影响加密主流程 |
| 已加密的存量文件（无 `.thumb`） | 本次不处理，仍显示占位；解密后重新加密即可获得封面 |
| copyTo 跨分区回退路径 | 缩略图在移动前提取，不受移动方式影响 |

## 预期结果

- 加密完成后，`/sdcard/InkPlayer/private/` 下每个 `.priv` 旁生成同名 `.thumb` 文件
- 加密文件夹列表正常显示视频封面
- 日志输出 `缩略图已加密保存: xxx.thumb (N bytes)`
- 主列表、播放、解密还原流程不受影响
