package com.ink.player.util

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 文件头部加解密工具
 *
 * 从用户密码通过 PBKDF2 派生 256 位密钥，加密/解密文件头部 16KB
 * 同一个密码 + 相同的盐值和参数 → 相同的密钥 → 可复现（卸载重装也能解密）
 */
object CryptoHelper {

    /** 加密头部大小 */
    private const val HEADER_SIZE = 16 * 1024 // 16KB

    /** GCM IV 长度（12 字节） */
    private const val GCM_IV_LENGTH = 12

    /** GCM 认证标签长度（128 位 = 16 字节） */
    private const val GCM_TAG_LENGTH = 128

    /** PBKDF2 迭代次数 */
    private const val PBKDF2_ITERATIONS = 100_000

    /** PBKDF2 密钥长度（256 位） */
    private const val KEY_LENGTH = 256

    /** 固定盐值（配合 PBKDF2，保证同密码同密钥） */
    private val SALT = byteArrayOf(
        0x49, 0x6E, 0x6B, 0x50, // "InkP"
        0x6C, 0x61, 0x79, 0x65, // "laye"
        0x72, 0x53, 0x61, 0x6C, // "rSal"
        0x74, 0x32, 0x30, 0x32  // "t202"
    )

    /**
     * 从密码派生 AES-256 密钥
     */
    fun deriveKey(password: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), SALT, PBKDF2_ITERATIONS, KEY_LENGTH)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * 加密文件头部 16KB
     *
     * 加密前：头部 16KB + 其余数据
     * 加密后：加密头部 + GCM Tag + IV 12字节 + 其余数据
     *
     * 只操作头部 + IV 区域，大文件其余部分不动，无 OOM 风险
     */
    fun encryptHeader(file: File, password: String) {
        val key = deriveKey(password)

        // 1. 生成随机 IV
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val fileSize = file.length()
        LogUtil.d("[CryptoHelper] 加密: file=${file.name}, size=$fileSize, password.len=${password.length}")
        val headerSize = minOf(fileSize, HEADER_SIZE.toLong()).toInt()
        if (headerSize == 0) return

        // 2. 只读取文件头部 16KB
        val plainHeader = ByteArray(headerSize)
        RandomAccessFile(file, "r").use { raf ->
            raf.read(plainHeader)
        }

        // 3. AES-GCM 加密头部（密文 = 原文 + 16字节 GCM tag）
        val encrypted = cipher.doFinal(plainHeader)

        // 4. 文件整体向后移动 (encrypted.size + 12 - headerSize) 字节，
        //    然后写回 [加密头部 + GCM Tag][IV]
        //    由于加密后比原文多 GCM tag(16) + IV(12) = 28 字节，
        //    但原文头部 16KB 可能短于文件，所以需要移动后面的数据
        val shift = encrypted.size + GCM_IV_LENGTH - headerSize
        if (shift > 0) {
            // 加密后变大了，需要把后面的数据往后移
            moveFileData(file, headerSize, shift)
        }

        // 5. 写回加密头部 + IV
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(encrypted)   // 加密头部 + GCM tag
            raf.write(iv)          // IV（12 字节）
        }

        LogUtil.d("[CryptoHelper] 加密文件头完成: ${file.name} ($headerSize bytes, shift=$shift)")
    }

    /**
     * 解密文件头部 16KB
     *
     * 解密前：加密头部 + GCM Tag + IV 12字节 + 其余数据
     * 解密后：解密头部 16KB + 其余数据
     *
     * 只操作头部区域，大文件其余部分不动，无 OOM 风险
     */
    fun decryptHeader(file: File, password: String): Boolean {
        val fileSize = file.length()
        LogUtil.d("[CryptoHelper] 解密: file=${file.name}, size=$fileSize, password.len=${password.length}")
        if (fileSize < GCM_IV_LENGTH + 16) return false // 至少要有 IV + GCM tag

        val key = deriveKey(password)

        // 加密后头部 = 原头部 + GCM tag(16字节)
        // 但我们不知道原头部大小，需要根据 GCM tag 推算
        // 加密后文件布局：[加密头部 + GCM tag][IV 12字节][其余数据]
        // 加密头部 + GCM tag 的大小 = min(原文件大小, 16KB) + 16
        // 但解密时原文件大小未知，需要尝试

        // 方案：先读 IV（在加密头部之后），然后读加密头部
        // 加密头部大小 = min(fileSize, HEADER_SIZE + 16)
        // 但如果文件比 16KB 小，加密头部 = fileSize - 12
        // 如果文件比 16KB 大，加密头部 = 16KB + 16 = 16400

        val encryptedHeaderSize = if (fileSize <= HEADER_SIZE + 16 + GCM_IV_LENGTH) {
            // 文件比头部小，加密头部 = fileSize - 12 (IV)
            (fileSize - GCM_IV_LENGTH).toInt()
        } else {
            // 文件比头部大，加密头部 = 16KB + 16(tag)
            HEADER_SIZE + 16
        }

        // 1. 读取 IV（加密头部之后 12 字节）
        val iv = ByteArray(GCM_IV_LENGTH)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(encryptedHeaderSize.toLong())
            raf.read(iv)
        }

        // 2. 读取加密头部（含 GCM tag）
        val encrypted = ByteArray(encryptedHeaderSize)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            raf.read(encrypted)
        }

        // 3. AES-GCM 解密
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        } catch (e: Exception) {
            LogUtil.d("[CryptoHelper] 密钥初始化失败: ${e.message}")
            return false
        }

        return try {
            val decrypted = cipher.doFinal(encrypted)
            LogUtil.d("[CryptoHelper] 解密成功: encrypted=$encryptedHeaderSize, decrypted=${decrypted.size}")

            // 4. 文件整体向前移动，移除 GCM tag + IV 占用的空间
            // 加密区域 = encryptedHeaderSize + IV(12)
            // 解密后头部 = decrypted.size
            // 需要移动的数据从 (encryptedHeaderSize + 12) 开始
            val shift = encryptedHeaderSize + GCM_IV_LENGTH - decrypted.size
            if (shift > 0) {
                moveFileData(file, encryptedHeaderSize + GCM_IV_LENGTH, -shift)
            }

            // 5. 写回解密头部
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(decrypted)
            }

            // 6. 截断文件末尾多余的字节
            val newSize = fileSize - shift
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(newSize)
            }

            LogUtil.d("[CryptoHelper] 解密文件头完成: ${file.name}, newSize=$newSize")
            true
        } catch (e: Exception) {
            LogUtil.d("[CryptoHelper] 解密失败: ${e.message}")
            false
        }
    }

    /**
     * 移动文件中 [startPos, end) 区域的数据，偏移量为 shift 字节
     * shift > 0 向后移，shift < 0 向前移
     */
    private fun moveFileData(file: File, startPos: Int, shift: Int) {
        val raf = RandomAccessFile(file, "rw")
        try {
            val fileSize = raf.length()
            val bufSize = 64 * 1024 // 64KB 缓冲区
            val buf = ByteArray(bufSize)

            if (shift > 0) {
                // 向后移动：从文件末尾往前复制
                var pos = fileSize - 1
                while (pos >= startPos) {
                    val chunkStart = maxOf(startPos.toLong(), pos - bufSize + 1).toLong()
                    val len = (pos - chunkStart + 1).toInt()
                    raf.seek(chunkStart)
                    raf.read(buf, 0, len)
                    raf.seek(chunkStart + shift)
                    raf.write(buf, 0, len)
                    pos -= len
                }
            } else {
                // 向前移动（shift 为负）：从 startPos 往后复制
                val moveLen = (-shift).toLong()
                var pos = startPos.toLong()
                val endPos = fileSize - moveLen
                while (pos < endPos) {
                    val len = minOf(bufSize.toLong(), endPos - pos).toInt()
                    raf.seek(pos + moveLen)
                    raf.read(buf, 0, len)
                    raf.seek(pos)
                    raf.write(buf, 0, len)
                    pos += len
                }
            }
        } finally {
            raf.close()
        }
    }

    /**
     * 全文件加密（用于缩略图等小文件）
     * 格式：[AES-GCM 密文 + Tag][IV 12字节]
     */
    fun encryptFullFile(file: File, password: String) {
        val key = deriveKey(password)

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val plainBytes = file.readBytes()
        val encrypted = cipher.doFinal(plainBytes)

        val newBytes = ByteArray(encrypted.size + GCM_IV_LENGTH)
        System.arraycopy(encrypted, 0, newBytes, 0, encrypted.size)
        System.arraycopy(iv, 0, newBytes, encrypted.size, GCM_IV_LENGTH)

        file.writeBytes(newBytes)
    }

    /**
     * 全文件解密（用于缩略图等小文件）
     * @return 解密后的字节数组
     */
    fun decryptFullFile(file: File, password: String): ByteArray {
        val key = deriveKey(password)

        val fileBytes = file.readBytes()
        val iv = fileBytes.copyOfRange(fileBytes.size - GCM_IV_LENGTH, fileBytes.size)
        val encrypted = fileBytes.copyOfRange(0, fileBytes.size - GCM_IV_LENGTH)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }
}
