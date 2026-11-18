package com.sopro.imagededup.scan

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.sopro.imagededup.model.ImageRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/**
 * 扫描分两阶段：
 * 1) 只做目录遍历，收集 URI 与基础信息，不解码任何图片；
 * 2) 逐张计算 pHash 与日期，处理完立刻释放位图。
 * 两个阶段都支持暂停与取消（通过协程取消 + checkPause 挂起闸门）。
 */
class ScanEngine(private val context: Context) {

    suspend fun enumerate(
        treeUri: Uri,
        onProgress: (Int) -> Unit,
        checkPause: suspend () -> Unit
    ): List<ImageRecord> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选文件夹，请重新授权")

        val result = ArrayList<ImageRecord>()
        // 显式栈，避免深层目录导致递归爆栈。
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to "")

        var nextId = 0L
        while (stack.isNotEmpty()) {
            checkPause()
            currentCoroutineContext().ensureActive()

            val (directory, prefix) = stack.removeLast()
            val children = try {
                directory.listFiles()
            } catch (e: SecurityException) {
                emptyArray()
            }

            for (child in children) {
                currentCoroutineContext().ensureActive()

                if (child.isDirectory) {
                    val childPath = if (prefix.isEmpty()) {
                        child.name ?: "未命名文件夹"
                    } else {
                        "$prefix/${child.name ?: "未命名文件夹"}"
                    }
                    stack.addLast(child to childPath)
                } else if (child.isFile && isSupportedImage(child.name, child.type)) {
                    result.add(
                        ImageRecord(
                            id = nextId++,
                            uri = child.uri,
                            name = child.name ?: child.uri.lastPathSegment.orEmpty(),
                            relativePath = prefix,
                            mimeType = child.type ?: guessMime(child.name),
                            sizeBytes = try { child.length() } catch (e: Exception) { 0L },
                            dateMillis = try { child.lastModified() } catch (e: Exception) { 0L }
                        )
                    )
                    if (result.size % 20 == 0) onProgress(result.size)
                }
            }
        }
        onProgress(result.size)
        return result
    }

    suspend fun analyzeAll(
        input: List<ImageRecord>,
        onProgress: (Int, Int) -> Unit,
        checkPause: suspend () -> Unit
    ): List<ImageRecord> {
        val output = input.toMutableList()
        for (index in output.indices) {
            checkPause()
            currentCoroutineContext().ensureActive()

            val record = output[index]
            val analysis = ImageAnalyzer.analyze(context, record.uri)
            val (dateMillis, dateText) = ImageAnalyzer.readDate(context, record.uri, record.dateMillis)

            output[index] = if (analysis != null) {
                record.copy(
                    width = analysis.width,
                    height = analysis.height,
                    phash = analysis.phash,
                    dateMillis = dateMillis,
                    dateText = dateText
                )
            } else {
                record.copy(dateMillis = dateMillis, dateText = dateText)
            }
            onProgress(index + 1, output.size)
        }
        return output
    }

    private fun isSupportedImage(name: String?, mime: String?): Boolean {
        val supportedMime = MIME_SET.contains(mime?.lowercase(Locale.ROOT).orEmpty())
        if (supportedMime) return true
        val lowerName = name?.lowercase(Locale.ROOT) ?: return false
        return EXTENSIONS.any { lowerName.endsWith(it) }
    }

    private fun guessMime(name: String?): String {
        val lower = name?.lowercase(Locale.ROOT).orEmpty()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    companion object {
        private val EXTENSIONS = arrayOf(".jpg", ".jpeg", ".png", ".webp")
        private val MIME_SET = setOf("image/jpeg", "image/png", "image/webp")
    }
}
