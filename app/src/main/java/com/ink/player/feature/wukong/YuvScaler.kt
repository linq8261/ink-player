package com.ink.player.feature.wukong

import android.media.Image
import android.media.MediaCodecInfo
import java.nio.ByteBuffer

/**
 * YUV 转换与缩放，用于 MediaCodec 转码管线。
 *
 * 流程：从解码器输出 [Image] 提取 YUV → 统一为 I420 中间格式 →
 * 双线性缩放到目标尺寸 → 打包成编码器要求的颜色格式（I420 / NV12 / flexible）。
 */
internal object YuvScaler {

    data class I420(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    /**
     * 从解码器输出 [Image] 提取 YUV 为 I420。
     * 自动处理 crop 区域、pixelStride/rowStride、planar / semiplanar(UV 交错) 布局。
     */
    fun extractToI420(image: Image): I420 {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        val planes = image.planes

        val y = readPlane(planes[0], crop.left, crop.top, w, h)
        val uvW = w / 2
        val uvH = h / 2
        val u = ByteArray(uvW * uvH)
        val v = ByteArray(uvW * uvH)

        if (planes.size >= 3) {
            // planar：plane[1]=U, plane[2]=V
            readPlaneInto(planes[1], crop.left / 2, crop.top / 2, uvW, uvH, u)
            readPlaneInto(planes[2], crop.left / 2, crop.top / 2, uvW, uvH, v)
        } else {
            // semiplanar：plane[1] 为交错 UV
            val uv = planes[1]
            val buf = uv.buffer
            val ps = uv.pixelStride
            val rs = uv.rowStride
            val start = crop.top / 2 * rs + crop.left / 2 * ps
            var i = 0
            for (row in 0 until uvH) {
                var p = start + row * rs
                for (col in 0 until uvW) {
                    u[i] = buf.get(p)
                    v[i] = buf.get(p + 1)
                    i++
                    p += ps
                }
            }
        }
        return I420(y, u, v)
    }

    /** 双线性缩放 I420，尺寸不变时直接返回原对象。 */
    fun scale(src: I420, srcW: Int, srcH: Int, dstW: Int, dstH: Int): I420 {
        if (srcW == dstW && srcH == dstH) return src
        val y = scalePlane(src.y, srcW, srcH, dstW, dstH)
        val u = scalePlane(src.u, srcW / 2, srcH / 2, dstW / 2, dstH / 2)
        val v = scalePlane(src.v, srcW / 2, srcH / 2, dstW / 2, dstH / 2)
        return I420(y, u, v)
    }

    /**
     * 把 I420 打包为编码器输入布局并写入 [out]，返回写入字节数。
     * [colorFormat] 取值见 [MediaCodecInfo.CodecCapabilities] 的 COLOR_FormatYUV420* 常量。
     */
    fun packTo(i420: I420, colorFormat: Int, out: ByteBuffer): Int {
        out.clear()
        out.put(i420.y)
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                out.put(i420.u)
                out.put(i420.v)
            }
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                val u = i420.u
                val v = i420.v
                val n = u.size
                val inter = ByteArray(n * 2)
                var j = 0
                for (i in 0 until n) {
                    inter[j++] = u[i]
                    inter[j++] = v[i]
                }
                out.put(inter)
            }
            else -> {
                // flexible / 其它：使用 planar 布局
                out.put(i420.u)
                out.put(i420.v)
            }
        }
        out.flip()
        return out.limit()
    }

    private fun readPlane(plane: Image.Plane, offX: Int, offY: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        readPlaneInto(plane, offX, offY, w, h, out)
        return out
    }

    private fun readPlaneInto(
        plane: Image.Plane, offX: Int, offY: Int, w: Int, h: Int, out: ByteArray
    ) {
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        var i = 0
        for (row in 0 until h) {
            var p = (offY + row) * rs + offX * ps
            if (ps == 1) {
                buf.position(p)
                buf.get(out, i, w)
                i += w
            } else {
                var col = 0
                while (col < w) {
                    out[i++] = buf.get(p)
                    p += ps
                    col++
                }
            }
        }
    }

    /** 双线性缩放单个平面（定点小数实现）。 */
    private fun scalePlane(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        if (srcW == dstW && srcH == dstH) return src
        val dst = ByteArray(dstW * dstH)

        val xs = IntArray(dstW)
        val xf = IntArray(dstW)
        for (x in 0 until dstW) {
            val sx = x * srcW / dstW
            xs[x] = sx
            xf[x] = ((x * srcW shl 8) / dstW) and 0xFF
        }
        val ys = IntArray(dstH)
        val yf = IntArray(dstH)
        for (y in 0 until dstH) {
            val sy = y * srcH / dstH
            ys[y] = sy
            yf[y] = ((y * srcH shl 8) / dstH) and 0xFF
        }
        val srcWm1 = srcW - 1
        val srcHm1 = srcH - 1

        var dstIdx = 0
        for (y in 0 until dstH) {
            val sy = ys[y]
            val fy = yf[y]
            val row0 = sy * srcW
            val row1 = (if (sy + 1 < srcH) sy + 1 else sy) * srcW
            for (x in 0 until dstW) {
                val sx = xs[x]
                val fx = xf[x]
                val sx1 = if (sx + 1 < srcW) sx + 1 else sx
                val a = src[row0 + sx].toInt() and 0xFF
                val b = src[row0 + sx1].toInt() and 0xFF
                val c = src[row1 + sx].toInt() and 0xFF
                val d = src[row1 + sx1].toInt() and 0xFF
                val top = a + (((b - a) * fx) shr 8)
                val bot = c + (((d - c) * fx) shr 8)
                dst[dstIdx++] = (top + (((bot - top) * fy) shr 8)).toByte()
            }
        }
        return dst
    }
}
