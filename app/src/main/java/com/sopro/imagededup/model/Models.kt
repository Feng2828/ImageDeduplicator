package com.sopro.imagededup.model

import android.net.Uri

/** 一张图片在本次扫描中的最小记录。所有字段都在后台线程填充。 */
data class ImageRecord(
    val id: Long,
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val dateText: String = "",
    val dateMillis: Long = 0L,
    val phash: Long = 0L
)

/**
 * 一组疑似重复图片。
 * keepId 与 toDelete 完全由用户在界面上决定，程序只在初始化时给出排序建议。
 */
data class DuplicateGroup(
    val id: Int,
    val members: List<Long>,
    val keepId: Long,
    val toDelete: MutableSet<Long> = mutableSetOf()
)

sealed interface ScanPhase {
    data object Idle : ScanPhase
    data class Enumerating(val found: Int) : ScanPhase
    data class Analyzing(val done: Int, val total: Int) : ScanPhase
    data object Grouping : ScanPhase
    data class Finished(val scanned: Int, val groups: Int, val involved: Int) : ScanPhase
    data class Failed(val message: String) : ScanPhase
}

data class DeleteOutcome(
    val deleted: Int,
    val failed: Int,
    val backupFolder: String?,
    val deletedIds: List<Long> = emptyList()
)
