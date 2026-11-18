package com.sopro.imagededup.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sopro.imagededup.model.DeleteOutcome
import com.sopro.imagededup.model.ImageRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 删除执行器。只有在 ViewModel 收到用户确认后才被调用。
 * 安全策略：开启备份时，任何一张备份失败都会跳过该张的删除。
 */
class DeletionManager(private val context: Context) {

    suspend fun delete(
        records: List<ImageRecord>,
        treeUri: Uri,
        backupEnabled: Boolean,
        onProgress: (Int, Int) -> Unit
    ): DeleteOutcome {
        val resolver = context.contentResolver
        var backupFolder: DocumentFile? = null

        if (backupEnabled) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            val folderName = "重复图片备份_" +
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(Date())
            backupFolder = root?.createDirectory(folderName)
            if (backupFolder == null) {
                // 备份目录创建失败：整个删除流程中止，绝不裸删。
                return DeleteOutcome(0, records.size, null)
            }
        }

        var deleted = 0
        var failed = 0
        val deletedIds = ArrayList<Long>()

        for ((index, record) in records.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(index + 1, records.size)

            val backupOkay = if (backupEnabled && backupFolder != null) {
                copyToBackup(resolver, record, backupFolder)
            } else {
                true
            }

            if (!backupOkay) {
                failed++
                continue
            }

            val removed = try {
                DocumentsContract.deleteDocument(resolver, record.uri)
            } catch (e: Exception) {
                false
            }

            if (removed) {
                deleted++
                deletedIds.add(record.id)
            } else {
                failed++
            }
        }

        return DeleteOutcome(deleted, failed, backupFolder?.name, deletedIds)
    }

    private fun copyToBackup(
        resolver: ContentResolver,
        record: ImageRecord,
        directory: DocumentFile
    ): Boolean {
        return try {
            val preferredMime = record.mimeType.ifBlank { "image/jpeg" }
            val fileName = record.name.ifBlank { "image_${record.id}" }

            var target = createWithRetry(directory, preferredMime, fileName)
            if (target == null && preferredMime != "application/octet-stream") {
                target = createWithRetry(directory, "application/octet-stream", fileName)
            }
            if (target == null) return false

            var copiedBytes = -1L
            resolver.openInputStream(record.uri)?.use { input ->
                resolver.openOutputStream(target.uri)?.use { output ->
                    copiedBytes = input.copyTo(output, 64 * 1024)
                    output.flush()
                }
            }
            if (copiedBytes < 0L) return false

            // 备份不完整时绝不删除原图，并移除这份残缺备份。
            if (record.sizeBytes > 0L && copiedBytes != record.sizeBytes) {
                try { target.delete() } catch (e: Exception) { }
                return false
            }
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    private fun createWithRetry(directory: DocumentFile, mime: String, name: String): DocumentFile? {
        var target = directory.createFile(mime, name)
        var suffix = 1
        while (target == null && suffix <= 99) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            target = directory.createFile(mime, "${base}_$suffix$extension")
            suffix++
        }
        return target
    }
}
