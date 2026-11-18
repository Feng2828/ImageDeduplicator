package com.sopro.imagededup.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class AnalysisResult(val phash: Long, val width: Int, val height: Int)

/**
 * 感知哈希计算：先按 inSampleSize 降采样，再缩放到 32x32 做 DCT，
 * 取低频 8x8 与中位数比较得到 64 位 pHash。
 * 单张图片任何时刻只存在一张 32x32 级别的小位图，绝不把原图整张读进内存。
 */
object ImageAnalyzer {
    private const val HASH_SIZE = 32
    private const val LOW_FREQ = 8

    private val cosTable: Array<FloatArray> = Array(HASH_SIZE) { u ->
        FloatArray(HASH_SIZE) { x ->
            Math.cos((2 * x + 1) * u * Math.PI / (2 * HASH_SIZE)).toFloat()
        }
    }

    fun analyze(context: Context, uri: Uri): AnalysisResult? {
        val resolver = context.contentResolver

        // 第一次只解析边界，不分配像素内存。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return null
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }

        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        // 目标约 32px，按 2 的幂取样；即使原图 12000x8000 也只会解码出 64x64 左右。
        var sampleSize = 1
        while (max(sourceWidth, sourceHeight) / sampleSize / 2 >= HASH_SIZE) {
            sampleSize *= 2
        }
        if (sampleSize < 1) sampleSize = 1

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val decoded: Bitmap = try {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        var square: Bitmap? = null
        try {
            square = if (decoded.width == HASH_SIZE && decoded.height == HASH_SIZE) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, HASH_SIZE, HASH_SIZE, true)
            }
            val hash = computePhash(square)
            return AnalysisResult(hash, sourceWidth, sourceHeight)
        } catch (e: Throwable) {
            return null
        } finally {
            if (square != null && square !== decoded) square.recycle()
            decoded.recycle()
        }
    }

    private fun computePhash(bitmap: Bitmap): Long {
        val gray = FloatArray(HASH_SIZE * HASH_SIZE)
        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        bitmap.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = 0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)
        }

        // 可分离二维 DCT：先行后列，只保留左上 8x8 低频系数。
        val rowDct = Array(HASH_SIZE) { FloatArray(LOW_FREQ) }
        for (y in 0 until HASH_SIZE) {
            for (u in 0 until LOW_FREQ) {
                var sum = 0f
                for (x in 0 until HASH_SIZE) {
                    sum += gray[y * HASH_SIZE + x] * cosTable[x][u]
                }
                rowDct[y][u] = sum
            }
        }

        val coefficient = FloatArray(LOW_FREQ * LOW_FREQ)
        var index = 0
        for (v in 0 until LOW_FREQ) {
            val scaleV = if (v == 0) 0.70710678f else 1f
            for (u in 0 until LOW_FREQ) {
                val scaleU = if (u == 0) 0.70710678f else 1f
                var sum = 0f
                for (y in 0 until HASH_SIZE) {
                    sum += rowDct[y][u] * cosTable[y][v]
                }
                coefficient[index++] = 0.25f * scaleU * scaleV * sum
            }
        }

        val sorted = coefficient.copyOf()
        sorted.sort()
        val median = (sorted[31] + sorted[32]) * 0.5f

        var hash = 0L
        for (i in coefficient.indices) {
            if (coefficient[i] > median) hash = hash or (1L shl i)
        }
        return hash
    }

    /** 优先读取 EXIF 拍摄时间，失败时回退到文件修改时间。 */
    fun readDate(context: Context, uri: Uri, fallback: Long): Pair<Long, String> {
        var millis = fallback
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (!raw.isNullOrBlank()) {
                    val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.CHINA)
                    parser.isLenient = false
                    millis = parser.parse(raw)?.time ?: fallback
                }
            }
        } catch (e: Exception) {
            // 部分文档提供者不支持随机读流，忽略并使用回退值。
        } catch (e: OutOfMemoryError) {
            // 同上。
        }

        if (millis <= 0L) millis = System.currentTimeMillis()
        val text = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
        } catch (e: Exception) {
            ""
        }
        return millis to text
    }
}
