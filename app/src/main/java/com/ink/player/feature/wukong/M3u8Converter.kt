package com.ink.player.feature.wukong

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 把悟空浏览器下载目录里的 TS 分片重编码成单个 MP4。
 *
 * 背景：源站 HLS 可能由多个不同规格（如 720p/25fps + 1080p/30fps）的视频段拼接而成，
 * 每段时间戳独立计时。纯 remux 无法处理规格切换与时间轴重置（会导致进度条错乱）。
 *
 * 方案：MediaCodec 重编码管线——
 *  1. 每个 TS 分片独立解封装 + 独立解码（段内规格一致，规避"轨道内换规格"）；
 *  2. 视频统一缩放到目标规格（固定 720p/24）重编码，音频重编码为 AAC（采样率取第一片）；
 *  3. 时间轴重建：段内保留原始 PTS，段与段之间按"内容时长 + 一帧"累加偏移，保证全程严格递增。
 *
 * 阻塞函数，需在 IO 线程调用。
 */
object M3u8Converter {

    private const val TAG = "M3u8Converter"

    private const val TARGET_WIDTH = 1280
    private const val TARGET_HEIGHT = 720
    private const val TARGET_FPS = 24
    /** 目标帧间隔（us），用于帧率归一化采样。 */
    private const val FRAME_US = 1_000_000L / TARGET_FPS
    private const val VIDEO_MIME = "video/avc"
    private const val AUDIO_MIME = "audio/mp4a-latm"
    private const val VIDEO_BITRATE = 8_000_000
    private const val AUDIO_BITRATE = 128_000
    private const val TIMEOUT_US = 10_000L

    /** decoder 排空后，encoder 连续无输出达到该次数即认为本段编码完成。 */
    private const val IDLE_THRESHOLD = 20

    /** 每轨累计时间戳偏移（us）。 */
    private class OffsetState(var video: Long = 0L, var audio: Long = 0L)

    /** 第一片轨道探测结果：音频采样率/声道数，以及流里是否含视频/音频轨。 */
    private class FirstTrackInfo(
        val sampleRate: Int,
        val channels: Int,
        val hasVideo: Boolean,
        val hasAudio: Boolean
    )

    /** muxer start 前暂存的一条待写样本。 */
    private class PendingSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val pts: Long,
        val flags: Int
    )

    /**
     * 延迟初始化的 muxer 状态。
     *
     * 某些设备的编码器必须收到真实输入才会输出 OUTPUT_FORMAT_CHANGED，因此不再"start 后立即死等 format"。
     * 而是等两轨编码器在转码中第一次输出 format 时才 addTrack，两轨齐了（或探测确认流里只有一轨）再 start。
     * start 之前的编码输出先暂存，start 后按序补写，保证 muxer 一定能启动、取消随时可中断。
     */
    private class MuxerState(
        private val muxer: MediaMuxer,
        private val hasVideo: Boolean,
        private val hasAudio: Boolean
    ) {
        private var vTrack = -1
        private var aTrack = -1
        private var started = false
        private val pending = ArrayList<PendingSample>()

        fun onVideoFormat(fmt: MediaFormat) {
            if (vTrack != -1 || started) return
            vTrack = muxer.addTrack(fmt)
            maybeStart()
        }

        fun onAudioFormat(fmt: MediaFormat) {
            if (aTrack != -1 || started) return
            aTrack = muxer.addTrack(fmt)
            maybeStart()
        }

        private fun maybeStart() {
            val need = (if (hasVideo) 1 else 0) + (if (hasAudio) 1 else 0)
            val have = (if (vTrack != -1) 1 else 0) + (if (aTrack != -1) 1 else 0)
            if (!started && have >= need) {
                muxer.start()
                started = true
                Log.d(TAG, "muxer.start(): vTrack=$vTrack aTrack=$aTrack")
                flushPending()
            }
        }

        /** 兜底：若探测与流不符导致一直没 start（如纯视频流被误判为有音频），按已有轨道强制 start。 */
        fun ensureStarted() {
            if (started) return
            if (vTrack != -1 || aTrack != -1) {
                muxer.start()
                started = true
                Log.d(TAG, "muxer.start()（ensureStarted 兜底）: vTrack=$vTrack aTrack=$aTrack")
                flushPending()
            }
        }

        fun writeVideoSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started && vTrack != -1) {
                muxer.writeSampleData(vTrack, buf, info)
            } else {
                pending.add(copySample(true, buf, info))
            }
        }

        fun writeAudioSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started && aTrack != -1) {
                muxer.writeSampleData(aTrack, buf, info)
            } else {
                pending.add(copySample(false, buf, info))
            }
        }

        private fun copySample(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo): PendingSample {
            val data = ByteArray(info.size)
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            buf.get(data)
            return PendingSample(isVideo, data, info.presentationTimeUs, info.flags)
        }

        private fun flushPending() {
            for (s in pending) {
                val track = if (s.isVideo) vTrack else aTrack
                if (track == -1) continue
                val info = MediaCodec.BufferInfo()
                info.set(0, s.data.size, s.pts, s.flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
            }
            pending.clear()
        }
    }

    /** 取消转换时抛出，用于中断当前管线并触发半成品文件清理。 */
    private class ConversionCancelledException : Exception()

    /**
     * 执行转换。阻塞函数，需在 IO 线程调用。
     *
     * @param task 扫描得到的任务
     * @param outputFile 输出 MP4 文件路径
     * @param onProgress 进度回调 (已完成分片数, 总分片数)
     * @return 输出的 MP4 文件
     */
    fun convert(
        task: VideoTask,
        outputFile: File,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean = { false },
        onSegmentProgress: (Float) -> Unit = {}
    ): File {
        val tsDir = File(task.tsDirPath)
        val segments = tsDir.listFiles { f -> f.name.endsWith(".ts", ignoreCase = true) }
            ?.sortedBy { it.name.replace(Regex("[^0-9]"), "").toLongOrNull() ?: Long.MAX_VALUE }
            ?: emptyList()
        if (segments.isEmpty()) throw IllegalStateException("未找到 ts 分片：${task.tsDirPath}")

        val keyBytes = task.keyFilePath?.let { p ->
            val kf = File(p)
            if (kf.exists()) kf.readBytes() else null
        }
        val ivBytes = parseIv(task.iv)
        val encrypted = keyBytes != null && keyBytes.size == 16

        // 提前上报总段数：让 UI 一进入转换就显示"第 1/N 片"，段内进度也能按比例推进
        onProgress(0, segments.size)
        Log.d(TAG, "convert 开始：${segments.size} 片，加密=$encrypted")

        // 探测第一片：音频采样率/声道，以及流里是否含视频/音频轨（供 muxer 延迟初始化判定）
        val firstTrack = probeFirstAudio(segments[0], keyBytes, ivBytes, encrypted)
        Log.d(TAG, "第一片轨探测：sampleRate=${firstTrack.sampleRate} channels=${firstTrack.channels} hasVideo=${firstTrack.hasVideo} hasAudio=${firstTrack.hasAudio}")

        val videoEnc = createVideoEncoder()
        val audioEnc = createAudioEncoder(firstTrack.sampleRate, firstTrack.channels)
        val videoColorFormat = pickVideoColorFormat(videoEnc)

        videoEnc.start()
        audioEnc.start()

        // muxer 延迟初始化：编码器可能收到真实输入才输出 format，不再死等。
        // 两轨 format 在转码中第一次出现时才 addTrack，两轨齐了（或流里只有一轨）再 start。
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerState = MuxerState(muxer, firstTrack.hasVideo, firstTrack.hasAudio)

        val offsets = OffsetState()
        try {
            for (i in segments.indices) {
                if (isCancelled()) throw ConversionCancelledException()
                val bytes = readSegment(segments[i], i, keyBytes, ivBytes, encrypted)
                processSegment(
                    bytes, videoEnc, audioEnc, muxerState,
                    videoColorFormat, offsets, firstTrack.sampleRate, firstTrack.channels, isCancelled, onSegmentProgress
                )
                onProgress(i + 1, segments.size)
            }
            flushEncoder(videoEnc, muxerState, true, isCancelled)
            flushEncoder(audioEnc, muxerState, false, isCancelled)
            muxerState.ensureStarted()
            muxer.stop()
        } catch (e: Exception) {
            runCatching { muxer.stop() }
            outputFile.delete()
            throw e
        } finally {
            runCatching { videoEnc.stop() }
            runCatching { audioEnc.stop() }
            runCatching { videoEnc.release() }
            runCatching { audioEnc.release() }
            muxer.release()
        }
        return outputFile
    }

    /** 处理单个分片：分别对视频轨、音频轨做"解码 → 重编码 → 写入 muxer"。 */
    private fun processSegment(
        bytes: ByteArray,
        videoEnc: MediaCodec,
        audioEnc: MediaCodec,
        muxerState: MuxerState,
        videoColorFormat: Int,
        offsets: OffsetState,
        expectedSampleRate: Int,
        expectedChannels: Int,
        isCancelled: () -> Boolean,
        onSegmentProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(ByteArrayMediaDataSource(bytes))
        var vDec: MediaCodec? = null
        var aDec: MediaCodec? = null
        try {
            var vIdx = -1
            var aIdx = -1
            var vMime: String? = null
            var aMime: String? = null
            for (t in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    vIdx == -1 && mime.startsWith("video/") -> { vIdx = t; vMime = mime }
                    aIdx == -1 && mime.startsWith("audio/") -> { aIdx = t; aMime = mime }
                }
            }

            if (vIdx != -1 && vMime != null) {
                vDec = MediaCodec.createDecoderByType(vMime)
                vDec.configure(extractor.getTrackFormat(vIdx), null, null, 0)
                vDec.start()
                processVideoSegment(
                    extractor, vIdx, vDec, videoEnc, muxerState, videoColorFormat,
                    offsets, isCancelled, onSegmentProgress
                )
            }
            if (aIdx != -1 && aMime != null) {
                aDec = MediaCodec.createDecoderByType(aMime)
                aDec.configure(extractor.getTrackFormat(aIdx), null, null, 0)
                aDec.start()
                processAudioSegment(
                    extractor, aIdx, aDec, audioEnc, muxerState,
                    offsets, expectedSampleRate, expectedChannels, isCancelled
                )
            }
        } finally {
            runCatching { vDec?.stop() }
            runCatching { vDec?.release() }
            runCatching { aDec?.stop() }
            runCatching { aDec?.release() }
            extractor.release()
        }
    }

    /**
     * 视频段处理：extractor → 解码器 → (YUV 提取 + 统一规格缩放) → 编码器 → muxer。
     * 段内用原始 PTS，段间通过 [OffsetState] 累加偏移。
     */
    private fun processVideoSegment(
        extractor: MediaExtractor,
        trackIdx: Int,
        dec: MediaCodec,
        enc: MediaCodec,
        muxerState: MuxerState,
        colorFormat: Int,
        offsets: OffsetState,
        isCancelled: () -> Boolean,
        onSegmentProgress: (Float) -> Unit
    ) {
        extractor.selectTrack(trackIdx)
        // 预估本段视频总时长（us）：优先取 track format 的 duration，失败再 seek 到末尾取最后一个样本 PTS
        var segDuration = 0L
        val trackFmt = extractor.getTrackFormat(trackIdx)
        if (trackFmt.containsKey(MediaFormat.KEY_DURATION)) {
            val d = trackFmt.getLong(MediaFormat.KEY_DURATION)
            if (d > 0) segDuration = d
        }
        if (segDuration <= 0) {
            extractor.seekTo(Long.MAX_VALUE, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val lastPts = extractor.sampleTime
            if (lastPts > 0) segDuration = lastPts
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val encInfo = MediaCodec.BufferInfo()
        var extractorEos = false
        var decoderEos = false
        var segMinPts: Long? = null
        var segMaxPts = 0L
        var frameCount = 0
        var srcW = TARGET_WIDTH
        var srcH = TARGET_HEIGHT
        var idle = 0
        // 帧率归一化：下一个应输出的 24fps 网格时间（us）
        var nextOutPts = -1L

        // 待喂入编码器的当前帧（最多暂存 1 帧，避免 decoder buffer 阻塞）
        var staged: YuvScaler.I420? = null
        var stagedPts = 0L
        var stagedFlags = 0

        while (true) {
            if (isCancelled()) throw ConversionCancelledException()
            // A. 把暂存帧喂给编码器（若有空间）
            val stagedFrame = staged
            if (stagedFrame != null) {
                val encIdx = enc.dequeueInputBuffer(0)
                if (encIdx >= 0) {
                    val encBuf = enc.getInputBuffer(encIdx)!!
                    val scaled = YuvScaler.scale(stagedFrame, srcW, srcH, TARGET_WIDTH, TARGET_HEIGHT)
                    val size = YuvScaler.packTo(scaled, colorFormat, encBuf)
                    enc.queueInputBuffer(encIdx, 0, size, stagedPts + offsets.video, stagedFlags)
                    staged = null
                }
            }

            // B. feed extractor -> decoder（暂存已消化时才继续取）
            if (!extractorEos && staged == null) {
                val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = dec.getInputBuffer(inIdx)!!
                    if (extractor.sampleTrackIndex == trackIdx) {
                        val size = extractor.sampleSize
                        inBuf.clear()
                        val read = extractor.readSampleData(inBuf, 0)
                        val pts = extractor.sampleTime
                        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        dec.queueInputBuffer(inIdx, 0, read, pts, flags)
                        extractor.advance()
                    } else {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorEos = true
                    }
                }
            }

            // C. drain decoder -> 暂存帧（提取为 I420 后立即释放 decoder buffer）
            if (staged == null) {
                val info = MediaCodec.BufferInfo()
                val outIdx = dec.dequeueOutputBuffer(info, 0)
                when (outIdx) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = dec.outputFormat
                        srcW = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        srcH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        if (fmt.containsKey(MediaFormat.KEY_CROP_RIGHT)) {
                            srcW = fmt.getInteger(MediaFormat.KEY_CROP_RIGHT) + 1
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CROP_BOTTOM)) {
                            srcH = fmt.getInteger(MediaFormat.KEY_CROP_BOTTOM) + 1
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                    else -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            dec.releaseOutputBuffer(outIdx, false)
                            decoderEos = true
                        } else {
                            val pts = info.presentationTimeUs
                            if (segMinPts == null) segMinPts = pts
                            segMaxPts = maxOf(segMaxPts, pts)
                            frameCount++
                            if (frameCount % 10 == 0 && segDuration > 0) {
                                val base = segMinPts ?: 0L
                                val span = segDuration - base
                                if (span > 0) {
                                    onSegmentProgress(((segMaxPts - base).toFloat() / span).coerceIn(0f, 1f))
                                }
                            }
                            // 帧率归一化到 TARGET_FPS：只在达到目标网格时间时采样一帧，
                            // 并用网格时间作为该帧 PTS（保证输出严格 24fps 递增）。
                            if (nextOutPts < 0) nextOutPts = pts
                            if (pts >= nextOutPts) {
                                val outPts = nextOutPts
                                nextOutPts += FRAME_US
                                val image = dec.getOutputImage(outIdx)
                                if (image != null) {
                                    try {
                                        staged = YuvScaler.extractToI420(image)
                                    } finally {
                                        image.close()
                                    }
                                    stagedPts = outPts
                                    stagedFlags = info.flags
                                }
                            }
                            dec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
            }

            // D. drain encoder -> muxer
            val encOutIdx = enc.dequeueOutputBuffer(encInfo, 0)
            when (encOutIdx) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (decoderEos && staged == null) idle++ else idle = 0
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerState.onVideoFormat(enc.outputFormat)
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                else -> {
                    idle = 0
                    if (encInfo.size > 0) {
                        val buf = enc.getOutputBuffer(encOutIdx)!!
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        muxerState.writeVideoSample(buf, encInfo)
                    }
                    enc.releaseOutputBuffer(encOutIdx, false)
                }
            }

            if (decoderEos && staged == null && idle >= IDLE_THRESHOLD) break
        }

        // 段结束：按"内容时长 + 一帧"累加视频偏移
        val base = segMinPts
        if (base != null && segMaxPts >= base) {
            offsets.video += (segMaxPts - base) + FRAME_US
        }
        onSegmentProgress(1f)
    }

    /**
     * 音频段处理：extractor → AAC 解码器 → (PCM 透传) → AAC 编码器 → muxer。
     * 暂不支持采样率/声道不一致（需要重采样），检测到不一致直接报错。
     */
    private fun processAudioSegment(
        extractor: MediaExtractor,
        trackIdx: Int,
        dec: MediaCodec,
        enc: MediaCodec,
        muxerState: MuxerState,
        offsets: OffsetState,
        expectedSampleRate: Int,
        expectedChannels: Int,
        isCancelled: () -> Boolean
    ) {
        extractor.selectTrack(trackIdx)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val encInfo = MediaCodec.BufferInfo()
        var extractorEos = false
        var decoderEos = false
        var segMinPts: Long? = null
        var segMaxPts = 0L
        var idle = 0

        var staged: ByteArray? = null
        var stagedPts = 0L
        var stagedFlags = 0

        while (true) {
            if (isCancelled()) throw ConversionCancelledException()
            // A. 暂存帧 -> encoder
            val stagedData = staged
            if (stagedData != null) {
                val encIdx = enc.dequeueInputBuffer(0)
                if (encIdx >= 0) {
                    val encBuf = enc.getInputBuffer(encIdx)!!
                    encBuf.clear()
                    encBuf.put(stagedData)
                    enc.queueInputBuffer(encIdx, 0, stagedData.size, stagedPts + offsets.audio, stagedFlags)
                    staged = null
                }
            }

            // B. feed extractor -> decoder
            if (!extractorEos && staged == null) {
                val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = dec.getInputBuffer(inIdx)!!
                    if (extractor.sampleTrackIndex == trackIdx) {
                        val size = extractor.sampleSize
                        inBuf.clear()
                        val read = extractor.readSampleData(inBuf, 0)
                        val pts = extractor.sampleTime
                        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        dec.queueInputBuffer(inIdx, 0, read, pts, flags)
                        extractor.advance()
                    } else {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorEos = true
                    }
                }
            }

            // C. drain decoder -> 暂存 PCM
            if (staged == null) {
                val info = MediaCodec.BufferInfo()
                val outIdx = dec.dequeueOutputBuffer(info, 0)
                when (outIdx) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = dec.outputFormat
                        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (sr != expectedSampleRate || ch != expectedChannels) {
                            throw IllegalStateException(
                                "音频格式不一致（$sr/$ch vs $expectedSampleRate/$expectedChannels），暂不支持重采样"
                            )
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                    else -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            dec.releaseOutputBuffer(outIdx, false)
                            decoderEos = true
                        } else {
                            val pts = info.presentationTimeUs
                            if (segMinPts == null) segMinPts = pts
                            segMaxPts = maxOf(segMaxPts, pts)
                            val buf = dec.getOutputBuffer(outIdx)!!
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(data)
                            staged = data
                            stagedPts = pts
                            stagedFlags = info.flags
                            dec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
            }

            // D. drain encoder -> muxer
            val encOutIdx = enc.dequeueOutputBuffer(encInfo, 0)
            when (encOutIdx) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (decoderEos && staged == null) idle++ else idle = 0
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerState.onAudioFormat(enc.outputFormat)
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                else -> {
                    idle = 0
                    if (encInfo.size > 0) {
                        val buf = enc.getOutputBuffer(encOutIdx)!!
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        muxerState.writeAudioSample(buf, encInfo)
                    }
                    enc.releaseOutputBuffer(encOutIdx, false)
                }
            }

            if (decoderEos && staged == null && idle >= IDLE_THRESHOLD) break
        }

        val base = segMinPts
        if (base != null && segMaxPts >= base) {
            // AAC 每帧 1024 samples
            val frameUs = 1_000_000L * 1024 / expectedSampleRate.coerceAtLeast(1)
            offsets.audio += (segMaxPts - base) + frameUs
        }
    }

    /** 向编码器发送 EOS 并排空剩余输出，写入 muxer。 */
    private fun flushEncoder(
        enc: MediaCodec,
        muxerState: MuxerState,
        isVideo: Boolean,
        isCancelled: () -> Boolean
    ) {
        val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx >= 0) {
            enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (isCancelled()) throw ConversionCancelledException()
            val outIdx = enc.dequeueOutputBuffer(info, TIMEOUT_US)
            when (outIdx) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                else -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        enc.releaseOutputBuffer(outIdx, false)
                        return
                    }
                    if (info.size > 0) {
                        val buf = enc.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (isVideo) muxerState.writeVideoSample(buf, info)
                        else muxerState.writeAudioSample(buf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                }
            }
        }
    }

    /** 创建统一规格的视频编码器（ByteBuffer 输入，颜色格式运行时选取）。 */
    private fun createVideoEncoder(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        val fmt = MediaFormat.createVideoFormat(VIDEO_MIME, TARGET_WIDTH, TARGET_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, pickVideoColorFormat(codec))
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /** 从编码器支持的格式里挑选 ByteBuffer 输入颜色格式：NV12 > I420 > flexible > 其它。 */
    private fun pickVideoColorFormat(codec: MediaCodec): Int {
        val fmts = codec.codecInfo.getCapabilitiesForType(VIDEO_MIME).colorFormats
        return when {
            fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            else -> fmts[0]
        }
    }

    /** 创建 AAC 音频编码器。 */
    private fun createAudioEncoder(sampleRate: Int, channels: Int): MediaCodec {
        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        val fmt = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /** 探测第一片：音频采样率/声道数，以及流里是否含视频/音频轨。 */
    private fun probeFirstAudio(
        file: File, key: ByteArray?, iv: ByteArray?, encrypted: Boolean
    ): FirstTrackInfo {
        return try {
            val bytes = if (encrypted) decryptSegment(file.readBytes(), key!!, iv ?: ByteArray(16))
            else file.readBytes()
            val ex = MediaExtractor()
            ex.setDataSource(ByteArrayMediaDataSource(bytes))
            var hasVideo = false
            var hasAudio = false
            var sr = 44100
            var ch = 2
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> hasVideo = true
                    mime.startsWith("audio/") -> {
                        hasAudio = true
                        sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            FirstTrackInfo(sr, ch, hasVideo, hasAudio)
        } catch (e: Exception) {
            // 探测失败按最保守处理：假设音视频都有，避免 muxer 因判定错误而过早 start
            Log.w(TAG, "probeFirstAudio 失败，回退 44100/2 且假定音视频都有", e)
            FirstTrackInfo(44100, 2, true, true)
        }
    }

    /** 读取单个分片（必要时 AES-128 解密）。 */
    private fun readSegment(
        file: File, idx: Int, key: ByteArray?, iv: ByteArray?, encrypted: Boolean
    ): ByteArray {
        val raw = FileInputStream(file).use { it.readBytes() }
        if (!encrypted) return raw
        val segIv = iv ?: run {
            val b = ByteArray(16)
            var v = idx.toLong()
            for (i in 15 downTo 0) {
                b[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            b
        }
        return decryptSegment(raw, key!!, segIv)
    }

    /** 解析 m3u8 里的 IV（形如 0x... 的 32 位十六进制）。 */
    private fun parseIv(iv: String?): ByteArray? {
        if (iv.isNullOrBlank()) return null
        val hex = iv.removePrefix("0x").removePrefix("0X").trim()
        if (hex.length != 32) return null
        return try {
            ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    /** 单个分片的 AES-128-CBC 解密（自动去 PKCS7 填充）。 */
    private fun decryptSegment(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** 把单个分片明文当作随机读取的字节流，供 MediaExtractor 使用。 */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = data.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() {}
    }
}
