package com.sopro.imagededup.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sopro.imagededup.data.DeletionManager
import com.sopro.imagededup.data.SettingsStore
import com.sopro.imagededup.model.DeleteOutcome
import com.sopro.imagededup.model.DuplicateGroup
import com.sopro.imagededup.model.ImageRecord
import com.sopro.imagededup.model.ScanPhase
import com.sopro.imagededup.scan.ScanEngine
import com.sopro.imagededup.scan.SimilarityGrouper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val scanEngine = ScanEngine(application)
    private val deletionManager = DeletionManager(application)

    private val _phase = MutableStateFlow<ScanPhase>(ScanPhase.Idle)
    val phase: StateFlow<ScanPhase> = _phase.asStateFlow()

    private val _groups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val groups: StateFlow<List<DuplicateGroup>> = _groups.asStateFlow()

    private val _records = MutableStateFlow<Map<Long, ImageRecord>>(emptyMap())
    val records: StateFlow<Map<Long, ImageRecord>> = _records.asStateFlow()

    private val _treeUri = MutableStateFlow(settings.treeUri?.let { Uri.parse(it) })
    val treeUri: StateFlow<Uri?> = _treeUri.asStateFlow()

    private val _folderName = MutableStateFlow(
        settings.treeUri?.let { displayName(application, it) } ?: ""
    )
    val folderName: StateFlow<String> = _folderName.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _threshold = MutableStateFlow(settings.threshold)
    val threshold: StateFlow<Int> = _threshold.asStateFlow()

    private val _backupEnabled = MutableStateFlow(settings.backupEnabled)
    val backupEnabled: StateFlow<Boolean> = _backupEnabled.asStateFlow()

    private val _message = Channel<String>(Channel.BUFFERED)
    val message = _message.receiveAsFlow()

    private var scanJob: Job? = null
    private var resumeGate: CompletableDeferred<Unit>? = null

    val isScanning: Boolean
        get() = when (_phase.value) {
            is ScanPhase.Enumerating, is ScanPhase.Analyzing, ScanPhase.Grouping -> true
            else -> false
        }

    // ---------- 设置 ----------

    fun setFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // 某些厂商桌面不支持持久化授权，本次会话内仍可使用。
        }
        settings.treeUri = uri.toString()
        _treeUri.value = uri
        _folderName.value = displayName(getApplication(), uri.toString())
    }

    fun setThreshold(value: Int) {
        val bounded = value.coerceIn(0, 7)
        settings.threshold = bounded
        _threshold.value = bounded
    }

    fun setBackupEnabled(value: Boolean) {
        settings.backupEnabled = value
        _backupEnabled.value = value
    }

    // ---------- 扫描控制 ----------

    fun startScan() {
        val tree = _treeUri.value
        if (tree == null) {
            viewModelScope.launch { _message.send("请先选择要扫描的文件夹") }
            return
        }
        if (scanJob?.isActive == true) return

        _groups.value = emptyList()
        _records.value = emptyMap()
        _paused.value = false
        resumeGate = null

        scanJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val limit = _threshold.value

                    val enumerated = scanEngine.enumerate(
                        treeUri = tree,
                        onProgress = { found -> _phase.value = ScanPhase.Enumerating(found) },
                        checkPause = { waitIfPaused() }
                    )

                    if (enumerated.isEmpty()) {
                        _phase.value = ScanPhase.Finished(0, 0, 0)
                        return@withContext
                    }

                    val analyzed = scanEngine.analyzeAll(
                        input = enumerated,
                        onProgress = { done, total -> _phase.value = ScanPhase.Analyzing(done, total) },
                        checkPause = { waitIfPaused() }
                    )

                    _phase.value = ScanPhase.Grouping
                    val grouped = SimilarityGrouper.group(analyzed, limit)

                    _records.value = analyzed.associateBy { it.id }
                    _groups.value = grouped
                    _phase.value = ScanPhase.Finished(
                        scanned = analyzed.size,
                        groups = grouped.size,
                        involved = grouped.sumOf { it.members.size }
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _phase.value = ScanPhase.Idle
                } catch (e: Exception) {
                    _phase.value = ScanPhase.Failed(e.message ?: "扫描失败，请重新选择文件夹")
                } finally {
                    _paused.value = false
                    resumeGate = null
                }
            }
        }
    }

    fun pauseScan() {
        if (resumeGate == null) resumeGate = CompletableDeferred()
        _paused.value = true
    }

    fun resumeScan() {
        resumeGate?.complete(Unit)
        resumeGate = null
        _paused.value = false
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        resumeGate?.complete(Unit)
        resumeGate = null
        _paused.value = false
        _phase.value = ScanPhase.Idle
    }

    private suspend fun waitIfPaused() {
        resumeGate?.await()
    }

    // ---------- 人工审核：所有变更只能来自用户操作 ----------

    fun setKeep(groupId: Int, recordId: Long) {
        _groups.value = _groups.value.map { group ->
            if (group.id != groupId) group
            else group.copy(
                keepId = recordId,
                toDelete = group.toDelete.toMutableSet().also { it.remove(recordId) }
            )
        }
    }

    fun toggleDelete(groupId: Int, recordId: Long) {
        _groups.value = _groups.value.map { group ->
            if (group.id != groupId || group.keepId == recordId) group
            else {
                val next = group.toDelete.toMutableSet()
                if (!next.add(recordId)) next.remove(recordId)
                group.copy(toDelete = next)
            }
        }
    }

    fun selectAllOthers(groupId: Int) {
        _groups.value = _groups.value.map { group ->
            if (group.id != groupId) group
            else group.copy(toDelete = group.members.filter { it != group.keepId }.toMutableSet())
        }
    }

    fun clearSelection(groupId: Int) {
        _groups.value = _groups.value.map { group ->
            if (group.id != groupId) group else group.copy(toDelete = mutableSetOf())
        }
    }

    /** 删除入口只从确认弹窗调用；未勾选任何图片时直接返回。 */
    fun deleteSelected() {
        if (_isDeleting.value) return
        _isDeleting.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { performDelete() }
            } finally {
                _isDeleting.value = false
            }
        }
    }

    private suspend fun performDelete() {
        val tree = _treeUri.value ?: return

        val currentRecords = _records.value
        val targets = ArrayList<ImageRecord>()
        _groups.value.forEach { group ->
            group.toDelete.forEach { id ->
                // 纵深防御：即便界面状态异常，被标记为保留的图片也绝不进入删除列表。
                if (id == group.keepId) return@forEach
                currentRecords[id]?.let { targets.add(it) }
            }
        }
        if (targets.isEmpty()) {
            _message.send("请先勾选要删除的副本")
            return
        }

        val outcome: DeleteOutcome = deletionManager.delete(
            records = targets,
            treeUri = tree,
            backupEnabled = _backupEnabled.value,
            onProgress = { _, _ -> }
        )

        if (outcome.deleted > 0) {
            // 只从界面移除真正删除成功的图片；失败的文件仍保留在列表中供复核。
            val removedIds = outcome.deletedIds.toSet()
            val remainingRecords = currentRecords.filterKeys { it !in removedIds }
            val remainingGroups = _groups.value
                .mapNotNull { group ->
                    val remain = group.members.filter { it !in removedIds }
                    if (remain.size >= 2) {
                        val keep = if (remain.contains(group.keepId)) group.keepId else remain.first()
                        group.copy(members = remain, keepId = keep, toDelete = mutableSetOf())
                    } else {
                        null
                    }
                }
                .mapIndexed { index, group -> if (group.id == index) group else group.copy(id = index) }

            _records.value = remainingRecords
            _groups.value = remainingGroups
        }

        val text = if (outcome.failed == 0) {
            val backupNote = outcome.backupFolder?.let { "；备份目录：$it" }.orEmpty()
            "已删除 ${outcome.deleted} 张图片$backupNote"
        } else {
            "已删除 ${outcome.deleted} 张，${outcome.failed} 张失败（备份失败的文件不会被删除）"
        }
        _message.send(text)
    }

    private fun displayName(context: Context, uriString: String): String {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(uriString))
            val parts = documentId.split(":")
            if (parts.size > 1) {
                parts[1].substringAfterLast('/').ifBlank { parts[1] }
            } else {
                documentId.substringAfterLast('/')
            }
        } catch (e: Exception) {
            uriString.substringAfterLast('/')
        }
    }
}
