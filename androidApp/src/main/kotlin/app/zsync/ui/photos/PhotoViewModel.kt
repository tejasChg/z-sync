package app.zsync.ui.photos

import app.zsync.log.QuickLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.zsync.gallery.CameraFile
import app.zsync.gallery.MediaFilter
import app.zsync.gallery.PhoneGallery
import app.zsync.gallery.SavedOriginal
import app.zsync.gallery.selectionAfterFilter
import app.zsync.gallery.visibleMedia
import app.zsync.gallery.GridCount
import app.zsync.gallery.downloadsOriginal
import app.zsync.gallery.statusAfterDelete
import app.zsync.gallery.PhotoLayout
import app.zsync.gallery.PhotoOrder
import app.zsync.gallery.planDelete
import app.zsync.gallery.QueueEvent
import app.zsync.gallery.TransferQueue
import app.zsync.gallery.beginSync
import app.zsync.gallery.handleFor
import app.zsync.gallery.identityKey
import app.zsync.gallery.pull
import app.zsync.gallery.reduce
import app.zsync.gallery.sameFileAs
import app.zsync.gallery.saveBatch
import app.zsync.gallery.syncBlockReason
import app.zsync.gallery.syncTransfer
import app.zsync.gallery.TransferFiles
import app.zsync.gallery.shouldLoadMore
import app.zsync.gallery.sortedPhotos
import app.zsync.image.addWatermark
import app.zsync.image.applyStyle
import app.zsync.link.LOG_TAG
import android.os.SystemClock
import app.zsync.gallery.PreviewLoadTracker
import app.zsync.ptp.CameraRoll
import app.zsync.ptp.KeepAliveOutcome
import app.zsync.ptp.KeepAliveVerdict
import app.zsync.ptp.KeepAlivePolicy
import app.zsync.ptp.PtpBreak
import app.zsync.ptp.REOPEN_WAITS_MS
import app.zsync.ptp.classifyPtpFailure
import app.zsync.ptp.PtpKind
import app.zsync.ptp.PtpRecovery
import app.zsync.ptp.RecoveryEffect
import app.zsync.ptp.RecoveryEvent
import app.zsync.ptp.RecoveryStep
import app.zsync.ptp.ReopenResult
import app.zsync.ptp.ShotInfo
import app.zsync.ptp.decodeForScreen
import app.zsync.ptp.recoveryChoices
import app.zsync.ptp.recoveryDetail
import app.zsync.ptp.recoveryStatus
import app.zsync.session.DisconnectCause
import app.zsync.session.TraceEvent
import app.zsync.ptp.reduce
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How often the idle keep-alive loop re-checks whether the camera needs a heartbeat. */
private const val KEEP_ALIVE_TICK_MS = 5_000L

/** Gap between thumbnails during the first catalog read; keeps the CPU from spiking flat out. */
private const val PREVIEW_PACE_MS = 12L

/** How often a paused grid re-checks whether the app is back on screen. */
private const val FOREGROUND_POLL_MS = 200L

/** Notification line while the session is up but nothing is transferring. */
private const val CAMERA_SESSION_IDLE_TEXT = "正在运行"

/**
 * The notification line for a running save, or null when there is nothing to say yet.
 *
 * Null before the first photo lands, so the notification keeps the plain "正在保存 N 张照片"
 * headline instead of jumping straight to "0/N", which reads as if the save went backwards.
 */
internal fun saveProgressLine(done: Int, total: Int): String? =
    if (done <= 0 || total <= 0) null else "正在保存 $done/$total 张照片"

data class SaveProgress(
    val handle: Long,
    val name: String,
    val fraction: Float,
    val label: String,
    val savedName: String? = null,
)

class PhotoViewModel(
    private val roll: CameraRoll,
    private val gallery: PhoneGallery,
    private val transfer: TransferFiles,
    private val onConfirmedCancel: () -> Unit,
    private val onSessionLost: () -> Unit,
    private val onSessionText: (String) -> Unit = {},
) : ViewModel() {
    var layout by mutableStateOf(PhotoLayout.GRID)
    var count by mutableStateOf(GridCount.NINE)
    var order by mutableStateOf(PhotoOrder.OLDEST)
    var filter by mutableStateOf(MediaFilter.All)
        private set
    var files by mutableStateOf(listOf<CameraFile>())
        private set
    var thumbs by mutableStateOf(mapOf<Long, ImageBitmap>())
        private set
    var total by mutableStateOf(0)
        private set
    var status by mutableStateOf("正在连接相机…")
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(setOf<Long>())
        private set
    var previewHandle by mutableStateOf<Long?>(null)
    var notice by mutableStateOf<String?>(null)
        private set
    var fullImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var fullHandle by mutableStateOf<Long?>(null)
        private set
    var fullStatus by mutableStateOf<String?>(null)
        private set
    var shots by mutableStateOf(mapOf<Long, ShotInfo>())
        private set
    var saves by mutableStateOf(listOf<SaveProgress>())
        private set
    var savedPreview by mutableStateOf<ImageBitmap?>(null)
        private set
    var savedPreviewHandle by mutableStateOf<Long?>(null)
        private set
    var savedPreviewStatus by mutableStateOf<String?>(null)
        private set
    var processStatus by mutableStateOf<String?>(null)
        private set
    private var started = false
    var loadingMore by mutableStateOf(false)
        private set
    var recoveryLine by mutableStateOf<String?>(null)
        private set
    var recoveryDetail by mutableStateOf<String?>(null)
        private set
    var recoveryActions by mutableStateOf<Pair<String, String>?>(null)
        private set
    var firstVisible by mutableStateOf(0)
        private set
    var lastVisible by mutableStateOf(0)
        private set
    private var wantedFull: Long? = null
    private var pumping = false
    private var saving = false
    private val pages = Mutex()
    private val recoveryGate = Mutex()
    private var recovery = PtpRecovery()
    private var recoverJob: Job? = null
    private var keepAliveJob: Job? = null
    private val keepAlive = KeepAlivePolicy()
    private val keepAliveVerdict = KeepAliveVerdict()
    @Volatile private var foreground = true

    /** Last line pushed to the notification, so per-chunk progress cannot spam it. */
    private var sessionSaveLine: String? = null
    private var sessionLostReported = false
    private val previewLoads = PreviewLoadTracker()
    var thumbnailsLoading by mutableStateOf(false)
        private set
    private val pendingDeletes = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var pausedSync: TransferQueue? = null
    private var pausedSyncPicks: List<CameraFile> = emptyList()

    fun abandonSession() {
        if (!started) return
        QuickLog.i(LOG_TAG, "abandon session: leaving the photo page, closing ptp")
        started = false
        loadingMore = false
        firstVisible = 0
        lastVisible = 0
        pumping = false
        wantedFull = null
        recoverJob?.cancel()
        keepAliveJob?.cancel()
        keepAlive.reset()
        previewLoads.reset()
        thumbnailsLoading = false
        recovery = PtpRecovery()
        recoveryLine = null
        recoveryDetail = null
        recoveryActions = null
        pausedSync = null
        pausedSyncPicks = emptyList()
        files = emptyList()
        thumbs = emptyMap()
        total = 0
        previewHandle = null
        fullImage = null
        fullHandle = null
        fullStatus = null
        loadFailed = false
        status = "正在连接相机…"
        viewModelScope.launch(Dispatchers.IO) {
            flushDeferredDeletes()
            // Whatever the camera could not clear now is still on the card and will reappear next
            // session. Drop the intent instead of letting a stale file linger in the grid.
            pendingDeletes.clear()
            roll.close()
        }
    }

    fun start() {
        if (started) return
        started = true
        loadFailed = false
        sessionLostReported = false
        keepAliveVerdict.reset()
        startKeepAlive()
        viewModelScope.launch(Dispatchers.IO) { openRoll() }
    }

    /**
     * Tells the grid whether the app is on screen.
     *
     * Filling thumbnails in the background pushed the CPU to 85%, and that is the load ColorOS reads
     * before it freezes the app. Loading therefore stops when the person looks elsewhere.
     */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        QuickLog.i(LOG_TAG, "screen foreground=$value")
    }

    private suspend fun awaitForeground() {
        while (!foreground) delay(FOREGROUND_POLL_MS)
    }

    /**
     * Keeps the camera's Wi-Fi alive while the app sits idle on the photo page. The camera drops the
     * hotspot when the PTP client goes quiet, so send a lightweight `GET_DEVICE_INFO` every interval.
     * `KeepAlivePolicy` decides when to fire and skips while a transfer is busy, so a download never
     * competes with the heartbeat.
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(KEEP_ALIVE_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                if (!keepAlive.shouldSend(now, busy = saving || !roll.hasSession)) continue
                keepAlive.markSent(now)
                if (heartbeat()) break
                flushDeferredDeletes()
            }
        }
    }

    /**
     * One idle heartbeat, and the decision that follows it.
     *
     * Returns true when the camera session is gone. That report matters: when the camera's Wi-Fi dies
     * without the system calling `onLost`, the person would otherwise sit on photos that can no longer
     * be read. The verdict never fires twice for one session.
     */
    private suspend fun heartbeat(): Boolean {
        if (sessionLostReported) return true
        val outcome = roll.keepaliveIfIdle()
        // One line per real heartbeat: a silent stretch in the file then means the ROM froze us.
        if (outcome != KeepAliveOutcome.Skipped) QuickLog.i(LOG_TAG, "heartbeat outcome=$outcome")
        if (!keepAliveVerdict.lost(outcome)) return false
        sessionLostReported = true
        QuickLog.e(LOG_TAG, "camera session lost on heartbeat outcome=$outcome")
        ui { status = "相机已断开" }
        onSessionLost()
        return true
    }

    /**
     * Clears the photos the person removed, in one batch, once the session is idle. Failing handles
     * stay queued for the next heartbeat, so a busy or dropped camera does not lose the intent.
     */
    private suspend fun flushDeferredDeletes() {
        val pending = pendingDeletes.toList()
        if (pending.isEmpty()) return
        val removed = roll.deleteAll(pending)
        pendingDeletes.removeAll(removed)
        if (removed.isNotEmpty()) {
            QuickLog.i(LOG_TAG, "ptp deferred delete flushed ${removed.size} of ${pending.size}")
        }
    }

    /** Leave a broken session (for example the camera network vanished) back to the connect page. */
    fun leaveBrokenSession() {
        onConfirmedCancel()
    }

    fun onScrolledTo(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        firstVisible = firstVisibleIndex
        lastVisible = lastVisibleIndex
        if (!shouldLoadMore(lastVisibleIndex, files.size, total) || loadingMore) return
        viewModelScope.launch(Dispatchers.IO) { append() }
    }

    fun toggle(handle: Long) {
        selected = if (handle in selected) selected - handle else selected + handle
    }

    fun chooseOrder(next: PhotoOrder) {
        if (order == next) return
        order = next
        files = sortedPhotos(files, next)
        viewModelScope.launch(Dispatchers.IO) { append() }
    }

    fun chooseFilter(next: MediaFilter) {
        if (filter == next) return
        filter = next
        val visible = visibleMedia(files, next)
        selected = selectionAfterFilter(selected, visible)
        if (previewHandle != null && visible.none { it.handle == previewHandle }) previewHandle = null
    }

    fun findVideo(file: CameraFile): Uri? = gallery.videoUri(file.name)

    fun open(handle: Long) {
        if (selecting) toggle(handle) else show(handle)
    }

    fun show(handle: Long) {
        previewHandle = handle
        wantedFull = handle
        val file = files.find { it.handle == handle } ?: return
        if (!downloadsOriginal(file.format)) {
            fullImage = null
            fullHandle = handle
            fullStatus = null
            return
        }
        if (fullHandle == handle && fullImage != null) {
            fullStatus = null
            return
        }
        fullImage = null
        fullHandle = handle
        fullStatus = "正在下载原片"
        if (pumping) return
        pumping = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pumpFull()
            } finally {
                ui { pumping = false }
            }
        }
    }

    fun showSaved(handle: Long) {
        savedPreviewHandle = handle
        savedPreview = null
        savedPreviewStatus = "正在打开原图"
        val row = saves.find { it.handle == handle }
        val name = (row?.savedName ?: row?.name).orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            val read = try {
                gallery.readSaved(name)
            } catch (failed: Exception) {
                QuickLog.e(LOG_TAG, "gallery read failed ${failed.javaClass.simpleName}: ${failed.message}")
                SavedOriginal.Missing
            }
            // A saved RAW has no sibling JPEG by default, but the file carries its own preview.
            val rawPreview = if (read is SavedOriginal.Raw) {
                try {
                    gallery.readEmbeddedPreview(name)
                } catch (failed: Exception) {
                    QuickLog.e(LOG_TAG, "raw preview failed ${failed.javaClass.simpleName}: ${failed.message}")
                    null
                }
            } else {
                null
            }
            QuickLog.i(LOG_TAG, "raw preview name=$name bytes=${rawPreview?.size ?: 0}")
            val bytes = (read as? SavedOriginal.Jpeg)?.bytes ?: rawPreview
            QuickLog.i(
                LOG_TAG,
                "decode saved handle=$handle kind=${read.javaClass.simpleName} " +
                    "bytes=${bytes?.size ?: 0} thread=${Thread.currentThread().name}",
            )
            val image = bytes?.let { decodeForScreen(it) }
            ui {
                if (savedPreviewHandle != handle) return@ui
                savedPreview = image
                savedPreviewStatus = when {
                    image != null && rawPreview != null -> "RAW 里内嵌的预览图"
                    image != null -> null
                    read is SavedOriginal.Raw -> "RAW 已保存到相册，这里显示的是相机缩略图"
                    read is SavedOriginal.Missing -> "相册里没有这张原图"
                    else -> "原图打不开"
                }
            }
        }
    }

    /**
     * Applies the watermark and filter engines to a saved JPEG and stores the result as a new
     * album row. Only the JPEG branch of [PhoneGallery.readSaved] can be decoded and re-encoded;
     * RAW and missing originals report a short status instead. The original bytes are reused as the
     * EXIF source, and every intermediate bitmap is recycled after compression.
     */
    fun processSaved(handle: Long, watermarkText: String, style: String, intensity: Float) {
        val row = saves.find { it.handle == handle }
        if (row == null) {
            processStatus = "找不到这张下载记录"
            return
        }
        val displayName = row.savedName ?: row.name
        processStatus = "正在处理 ${row.name}…"
        viewModelScope.launch(Dispatchers.IO) {
            val original = try {
                gallery.readSaved(displayName)
            } catch (failed: Exception) {
                QuickLog.e(
                    LOG_TAG,
                    "process read failed name=$displayName ${failed.javaClass.simpleName}: ${failed.message}",
                )
                ui { processStatus = "读取原图失败" }
                return@launch
            }
            if (original !is SavedOriginal.Jpeg) {
                ui { processStatus = "仅支持 JPEG" }
                return@launch
            }

            val originalBytes = original.bytes
            val decoded = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            if (decoded == null) {
                ui { processStatus = "原图无法解码" }
                return@launch
            }

            var watermarked: Bitmap? = null
            var styled: Bitmap? = null
            try {
                val watermarkedBitmap = addWatermark(decoded, watermarkText)
                watermarked = watermarkedBitmap
                val styledBitmap = applyStyle(watermarkedBitmap, style, intensity)
                styled = styledBitmap
                val processedBytes = ByteArrayOutputStream().use { output ->
                    if (!styledBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                        error("JPEG 压缩失败")
                    }
                    output.toByteArray()
                }
                val newName = displayName.substringBeforeLast('.', displayName) + "_watermarked.jpg"
                val savedName = gallery.saveProcessed(newName, processedBytes, originalBytes)
                ui { processStatus = "已保存 $savedName" }
            } catch (failed: Exception) {
                QuickLog.e(
                    LOG_TAG,
                    "process save failed name=$displayName ${failed.javaClass.simpleName}: ${failed.message}",
                )
                ui { processStatus = "处理失败：${failed.message ?: failed.javaClass.simpleName}" }
            } finally {
                if (styled != null && styled !== watermarked) styled.recycle()
                if (watermarked != null && watermarked !== decoded) watermarked.recycle()
                decoded.recycle()
            }
        }
    }

    fun confirmDelete(handles: Set<Long>) {
        val plan = planDelete(files, handles)
        if (plan.remove.isEmpty()) {
            notice = lockedNotice(plan.keptLocked)
            return
        }
        // Hide now, clear on the next idle heartbeat. Deleting the photo the camera is showing
        // forces its screen into shooting mode, so the app batches the removes and sends them when
        // the person is no longer picking photos.
        pendingDeletes.addAll(plan.remove)
        val remaining = files.filter { it.handle !in plan.remove }
        files = remaining
        thumbs = thumbs - plan.remove
        total = (total - plan.remove.size).coerceAtLeast(0)
        selected = selected - plan.remove
        if (previewHandle != null && remaining.none { it.handle == previewHandle }) previewHandle = null
        status = statusAfterDelete(files.size, status)
        notice = lockedNotice(plan.keptLocked)
    }

    private fun lockedNotice(names: List<String>): String? {
        return names.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "$it 受保护，留在相机里。" }
    }

    fun changeSelecting(on: Boolean) {
        selecting = on
        if (!on) selected = emptySet()
    }

    fun showNotice(text: String) {
        notice = text
    }

    fun saveSelected() {
        if (saving) return
        syncBlockReason(saving = saving, previewsLoading = thumbnailsLoading)?.let { blocked ->
            QuickLog.i(LOG_TAG, "save blocked thumbnailsLoading=$thumbnailsLoading loadingMore=$loadingMore")
            notice = blocked
            return
        }
        val batch = saveBatch(files, selected) ?: return
        viewModelScope.launch(Dispatchers.IO) { runSaveBatch(batch.picks, batch.selecting) }
    }

    private suspend fun runSaveBatch(picks: List<CameraFile>, leaveSelection: Boolean) {
        saving = true
        sessionSaveLine = null
        onSessionText("正在保存 ${picks.size} 张照片")
        saves = picks.map { SaveProgress(it.handle, it.name, 0f, "等待") }
        changeSelecting(leaveSelection)
        var saved = 0
        var skipped = 0
        var failed = 0
        try {
            QuickLog.i(LOG_TAG, "save batch thread=${Thread.currentThread().name} count=${picks.size}")
            val toFetch = mutableListOf<CameraFile>()
            for (file in picks) {
                if (gallery.alreadyThere(file)) {
                    skipped += 1
                    postSave(file.handle, 1f, "已跳过")
                    QuickLog.i(LOG_TAG, "save skipped name=${file.name} handle=${file.handle}")
                } else {
                    postSave(file.handle, 0.01f, "0%")
                    toFetch += file
                }
            }
            if (toFetch.isNotEmpty()) {
                val queue = pausedSync?.takeIf { pausedSyncPicks == toFetch }
                    ?: beginSync(toFetch, selecting = false).queue
                pausedSync = null
                val outcome = syncWithQueue(queue, toFetch)
                saved += outcome.saved
                failed += outcome.failed
                if (outcome.paused) {
                    pausedSync = outcome.queue
                    pausedSyncPicks = toFetch
                    notePausedQueue(toFetch)
                    ui { notice = "保存中断：正在恢复相机连接" }
                    return
                }
            }
            ui { notice = saveNotice(saved, skipped, failed) }
        } finally {
            ui {
                saving = false
                changeSelecting(false)
            }
            sessionSaveLine = null
            onSessionText(CAMERA_SESSION_IDLE_TEXT)
        }
    }

    private suspend fun resumeSaveIfPaused() {
        val queue = pausedSync ?: return
        val picks = pausedSyncPicks
        if (picks.isEmpty()) return
        saving = true
        try {
            val outcome = syncWithQueue(queue, picks)
            if (outcome.paused) {
                pausedSync = outcome.queue
                notePausedQueue(picks)
                return
            }
            pausedSync = null
            pausedSyncPicks = emptyList()
            val skipped = picks.count { gallery.alreadyThere(it) }
            ui { notice = saveNotice(outcome.saved, skipped, outcome.failed) }
        } finally {
            ui { saving = false }
        }
    }

    private data class SyncOutcome(val saved: Int, val failed: Int, val paused: Boolean, val queue: TransferQueue)

    private suspend fun syncWithQueue(start: TransferQueue, targets: List<CameraFile>): SyncOutcome {
        var queue = seedPartial(start, targets)
        var saved = 0
        var failed = 0
        val ready = publishReady(queue, targets)
        queue = ready.queue
        saved += ready.saved
        failed += ready.failed
        val pulled = pull(queue) { chunk ->
            val handle = handleFor(queue, chunk.identity) ?: throw IOException("missing handle")
            val file = targets.first { chunk.identity.sameFileAs(it) }
            val base = chunk.offset
            val bytes = readKeepingLease {
                val data = roll.readPartial(handle, chunk.offset, chunk.count) { seen ->
                    if (file.bytes > 0L) {
                        val fraction = ((base + seen).toFloat() / file.bytes.toFloat()).coerceIn(0.01f, 1f)
                        val label = if (fraction >= 1f) "已下载" else "${(fraction * 100).toInt()}%"
                        postSave(file.handle, fraction, label)
                    }
                } ?: throw IOException("ptp closed")
                if (data.isEmpty()) throw IOException("ptp returned no data for chunk offset=${chunk.offset}")
                data
            } ?: throw IOException("ptp closed")
            transfer.append(chunk.identity, bytes)
            bytes.toList()
        }
        queue = pulled.queue
        val stored = publishReady(queue, targets)
        queue = stored.queue
        saved += stored.saved
        failed += stored.failed
        return SyncOutcome(saved, failed, paused = !pulled.finished, queue)
    }

    private fun seedPartial(queue: TransferQueue, targets: List<CameraFile>): TransferQueue {
        var current = queue
        for (file in targets) {
            val identity = file.syncTransfer().identity
            val key = identityKey(identity)
            if (key in current.received || key in current.committed || transfer.publishable(identity)) continue
            val prior = transfer.length(identity)
            if (prior <= 0L) continue
            current = current.copy(received = current.received + (key to prior))
        }
        return current
    }

    private suspend fun publishReady(queue: TransferQueue, targets: List<CameraFile>): Published {
        var current = queue
        var saved = 0
        var failed = 0
        for (file in targets) {
            val identity = file.syncTransfer().identity
            val source = transfer.source(identity) ?: continue
            try {
                postSave(file.handle, 1f, "保存中")
                val savedName = gallery.save(file, source)
                transfer.discard(identity)
                saved += 1
                postSaved(file.handle, savedName)
                QuickLog.i(LOG_TAG, "save stored name=$savedName handle=${file.handle} bytes=${file.bytes}")
                current = reduce(current, QueueEvent.Saved(identity)).queue
            } catch (failedSave: Exception) {
                QuickLog.e(LOG_TAG, "save failed ${failedSave.javaClass.simpleName}: ${failedSave.message}")
                failed += 1
                postSave(file.handle, 0f, "失败")
                current = reduce(current, QueueEvent.Saved(identity)).queue
            } catch (oom: OutOfMemoryError) {
                QuickLog.e(LOG_TAG, "save ran out of memory handle=${file.handle}")
                failed += 1
                postSave(file.handle, 0f, "失败")
                current = reduce(current, QueueEvent.Saved(identity)).queue
            }
        }
        return Published(current, saved, failed)
    }

    private data class Published(val queue: TransferQueue, val saved: Int, val failed: Int)

    private fun saveNotice(saved: Int, skipped: Int, failed: Int): String = when {
        saved == 0 && skipped == 0 -> "没有保存成功"
        failed == 0 && skipped == 0 -> "已保存 $saved 张到相册"
        failed == 0 && saved == 0 -> "已在相册，跳过 $skipped 张"
        failed == 0 -> "已保存 $saved 张到相册，跳过 $skipped 张"
        else -> "已保存 $saved 张，跳过 $skipped 张，$failed 张失败"
    }

    fun deleteSaved(handle: Long) {
        val row = saves.find { it.handle == handle } ?: return
        val name = row.savedName ?: row.name
        viewModelScope.launch(Dispatchers.IO) {
            val removed = try {
                gallery.remove(name)
            } catch (failed: Exception) {
                QuickLog.e(LOG_TAG, "gallery delete failed ${failed.javaClass.simpleName}: ${failed.message}")
                0
            }
            ui {
                if (removed == 0) {
                    notice = "没有从手机相册删掉 $name"
                } else {
                    saves = saves.filter { it.handle != handle }
                    if (savedPreviewHandle == handle) {
                        savedPreviewHandle = null
                        savedPreview = null
                        savedPreviewStatus = null
                    }
                    notice = "已从手机相册删除 $name"
                }
            }
        }
    }

    private fun postSave(handle: Long, fraction: Float, label: String) {
        viewModelScope.launch(Dispatchers.Main) { markSave(handle, fraction, label) }
    }

    private fun postSaved(handle: Long, savedName: String) {
        viewModelScope.launch(Dispatchers.Main) {
            saves = saves.map { row ->
                if (row.handle != handle) row else row.copy(fraction = 1f, label = "已保存", savedName = savedName)
            }
            publishSaveProgress()
        }
    }

    /**
     * Keeps the notification's photo counter current.
     *
     * The long part of a save is the download, and it only ever reported chunk progress to the
     * grid. Without this the notification sat on "正在保存 3 张照片" until the very end and then
     * flashed 1/3, 2/3, 3/3 within a few milliseconds.
     */
    private fun publishSaveProgress() {
        val line = saveProgressLine(saves.count { it.fraction >= 1f }, saves.size) ?: return
        if (line == sessionSaveLine) return
        sessionSaveLine = line
        onSessionText(line)
    }

    private suspend fun ui(block: () -> Unit) {
        withContext(Dispatchers.Main) { block() }
    }

    private fun markSave(handle: Long, fraction: Float, label: String) {
        if (saves.none { it.handle == handle }) return
        saves = saves.map { row ->
            if (row.handle == handle) row.copy(fraction = fraction, label = label) else row
        }
        publishSaveProgress()
    }

    fun clearNotice() {
        notice = null
    }

    fun continueRecovery() {
        viewModelScope.launch(Dispatchers.IO) {
            val start = recoveryGate.withLock {
                val next = reduce(recovery, RecoveryEvent.Continue)
                recovery = next.state
                RecoveryEffect.START_ATTEMPT in next.effects
            }
            publishRecovery()
            if (!start) return@launch
            when (roll.reopenSession()) {
                ReopenResult.Opened -> recoveryGate.withLock {
                    recovery = recovery.copy(
                        step = RecoveryStep.Ready,
                        sessionOpen = true,
                        queuePaused = false,
                        eventReading = true,
                    )
                }
                ReopenResult.NoNetwork ->
                    QuickLog.e(LOG_TAG, "ptp reopen skipped: camera network missing; Wi-Fi lease kept")
                ReopenResult.StillClosed -> notePtpBreak()
            }
            publishRecovery()
            if (recovery.step == RecoveryStep.Ready && recovery.sessionOpen) showRollStatus()
        }
    }

    fun cancelRecovery() {
        viewModelScope.launch(Dispatchers.IO) {
            val release = recoveryGate.withLock {
                val next = reduce(recovery, RecoveryEvent.Cancel)
                recovery = next.state
                RecoveryEffect.RELEASE_LEASE in next.effects
            }
            publishRecovery()
            if (!release) return@launch
            roll.close()
            transfer.discardIncomplete()
            onConfirmedCancel()
        }
    }

    override fun onCleared() {
        keepAliveJob?.cancel()
        runBlocking(Dispatchers.IO) {
            flushDeferredDeletes()
            pendingDeletes.clear()
        }
        roll.close()
    }

    private suspend fun openRoll() {
        ui { loadingMore = true }
        try {
            val count = readKeepingLease { loadListedRoll() } ?: return
            QuickLog.i(LOG_TAG, "ptp handles=$count thread=${Thread.currentThread().name}")
            if (files.size < count) append()
        } catch (failed: Exception) {
            QuickLog.e(LOG_TAG, "ptp failed ${failed.javaClass.simpleName}: ${failed.message}")
            ui {
                loadFailed = true
                status = "读不到照片：${failed.message}"
            }
            onSessionLost()
        } finally {
            ui { loadingMore = false }
        }
    }

    private suspend fun loadListedRoll(): Int {
        previewLoads.begin()
        ui { thumbnailsLoading = previewLoads.isLoading }
        try {
            val count = roll.open(
                onStep = { step -> ui { if (files.isEmpty()) status = step } },
                pace = { delay(PREVIEW_PACE_MS) },
                onPreview = { batch ->
                    ui {
                        files = sortedPhotos(files + batch.map { it.file }, order)
                        thumbs = thumbs + batch.mapNotNull { thumb -> thumb.image?.let { thumb.file.handle to it } }
                        status = "正在加载照片，已显示 ${files.size} 张"
                    }
                },
            )
            ui {
                total = count
                status = if (count == 0) "相机里没有照片" else "已显示 ${files.size} / $count 张"
            }
            return count
        } finally {
            previewLoads.end()
            ui { thumbnailsLoading = previewLoads.isLoading }
        }
    }

    private suspend fun append() {
        if (!pages.tryLock()) return
        // Early exit must not touch the preview tracker: during the first catalog read `total` is
        // still zero, so a scroll can call append() before any thumbnails were requested. If this
        // return were inside the try, the finally below would decrement a counter that was never
        // incremented and clear `thumbnailsLoading` while the grid is still filling.
        if (files.size >= total) return
        try {
            ui { loadingMore = true }
            previewLoads.begin()
            ui { thumbnailsLoading = previewLoads.isLoading }
            val batch = readKeepingLease { roll.next(order, yield = { pumping }, paused = ::awaitForeground) }
                ?: return
            if (batch.thumbs.isEmpty() && batch.yielded) return
            ui {
                if (batch.thumbs.isEmpty()) {
                    total = files.size
                    status = if (files.isEmpty()) "相机里没有照片" else "已显示 ${files.size} / $total 张"
                } else {
                    files = sortedPhotos(files + batch.thumbs.map { it.file }, order)
                    thumbs = thumbs + batch.thumbs.mapNotNull { thumb -> thumb.image?.let { thumb.file.handle to it } }
                    status = "已显示 ${files.size} / $total 张"
                    QuickLog.i(LOG_TAG, "ptp thumbs ${files.size} of $total")
                }
            }
        } catch (failed: Exception) {
            QuickLog.e(LOG_TAG, "ptp batch failed ${failed.javaClass.simpleName}: ${failed.message}")
            ui { status = "缩略图中断：${failed.message}" }
        } finally {
            try {
                previewLoads.end()
                ui {
                    thumbnailsLoading = previewLoads.isLoading
                    loadingMore = false
                }
            } finally {
                pages.unlock()
            }
        }
    }

    private suspend fun pumpFull() {
        while (true) {
            val target = wantedFull ?: return
            val file = files.find { it.handle == target } ?: return
            if (!downloadsOriginal(file.format)) {
                ui {
                    fullImage = null
                    fullHandle = target
                    fullStatus = null
                }
                if (wantedFull == target) return
                continue
            }
            if (fullHandle == target && fullImage != null && fullStatus == null) {
                if (wantedFull == target) return
                continue
            }
            ui { fullStatus = "正在下载原片" }
            val frame = try {
                readKeepingLease { roll.full(target) } ?: run {
                    ui { fullStatus = "原片下载中断" }
                    return
                }
            } catch (failed: Exception) {
                QuickLog.e(LOG_TAG, "ptp object failed ${failed.javaClass.simpleName}: ${failed.message}")
                if (wantedFull == target) ui { fullStatus = "原片下载中断" }
                return
            } catch (oom: OutOfMemoryError) {
                QuickLog.e(LOG_TAG, "decode ran out of memory handle=$target")
                if (wantedFull == target) ui { fullStatus = "原片打不开" }
                return
            }
            if (wantedFull != target) continue
            ui {
                fullImage = frame.image
                fullHandle = target
                shots = shots + (target to frame.shot)
                fullStatus = if (frame.image == null) "原片打不开" else null
            }
            if (wantedFull == target) return
        }
    }

    private suspend fun <T> readKeepingLease(block: suspend () -> T): T? {
        try {
            return block()
        } catch (failed: Exception) {
            val reason = classifyPtpFailure(failed) ?: throw failed
            QuickLog.e(LOG_TAG, "ptp connection failed ${failed.message}")
            if (reason == PtpBreak.SessionNotOpen || RecoveryEffect.RETRY_READ !in notePtpBreak(reason)) return null
        }
        return try {
            block()
        } catch (failed: Exception) {
            val reason = classifyPtpFailure(failed) ?: throw failed
            QuickLog.e(LOG_TAG, "ptp connection failed ${failed.message}")
            notePtpBreak(reason)
            null
        }
    }

    private suspend fun notePtpBreak(reason: PtpBreak = PtpBreak.CameraClosed): List<RecoveryEffect> {
        val effects = recoveryGate.withLock {
            val next = reduce(
                recovery,
                RecoveryEvent.Failed(
                    PtpKind.Read,
                    reason,
                    SystemClock.elapsedRealtime(),
                    recovery.generation,
                ),
            )
            recovery = next.state
            next.effects
        }
        if (recovery.step != RecoveryStep.RetryingRead) {
            roll.trace.record(
                TraceEvent.Recovery(
                    traceId = roll.trace.id,
                    level = recovery.step.name,
                    attempt = recovery.reopenSlot + 1,
                    reason = reason.name,
                    elapsedMs = REOPEN_WAITS_MS.getOrElse(recovery.reopenSlot) { 0L },
                ),
            )
        }
        if (recovery.step == RecoveryStep.WaitingToReopen && recovery.reopenSlot == 0) {
            roll.trace.record(TraceEvent.Ended(roll.trace.id, DisconnectCause.PtpFirst))
        }
        publishRecovery()
        if (recovery.step == RecoveryStep.WaitingToReopen) scheduleReopen()
        return effects
    }

    private fun notePausedQueue(picks: List<CameraFile>) {
        picks.forEach { file ->
            val safe = transfer.length(file.syncTransfer().identity)
            if (safe <= 0L || safe >= file.bytes) return@forEach
            roll.trace.record(
                TraceEvent.Queue(
                    traceId = roll.trace.id,
                    taskId = file.name,
                    safeBytes = safe,
                    result = "paused",
                ),
            )
        }
    }

    private fun scheduleReopen() {
        val due = recovery.waitUntil ?: return
        val generation = recovery.generation
        recoverJob?.cancel()
        recoverJob = viewModelScope.launch(Dispatchers.IO) {
            val wait = due - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            val opening = recoveryGate.withLock {
                if (recovery.generation != generation || recovery.step != RecoveryStep.WaitingToReopen) {
                    false
                } else {
                    val tick = reduce(recovery, RecoveryEvent.Tick(SystemClock.elapsedRealtime()))
                    recovery = tick.state
                    RecoveryEffect.OPEN_SESSION in tick.effects
                }
            }
            if (!opening) return@launch
            publishRecovery()
            finishReopen(generation)
        }
    }

    private suspend fun finishReopen(generation: Long) {
        val result = if (roll.total > 0) {
            roll.reopenSession()
        } else {
            try {
                loadListedRoll()
                ReopenResult.Opened
            } catch (failed: Exception) {
                QuickLog.e(LOG_TAG, "ptp reopen failed ${failed.javaClass.simpleName}: ${failed.message}")
                ReopenResult.StillClosed
            }
        }
        when (result) {
            ReopenResult.Opened -> {
                recoveryGate.withLock {
                    val next = reduce(recovery, RecoveryEvent.SessionOpened(generation))
                    recovery = next.state
                }
                publishRecovery()
                if (recovery.step == RecoveryStep.Ready && recovery.sessionOpen) showRollStatus()
            }
            ReopenResult.NoNetwork ->
                QuickLog.e(LOG_TAG, "ptp reopen skipped: camera network missing; Wi-Fi lease kept")
            ReopenResult.StillClosed -> {
                val waiting = recoveryGate.withLock {
                    val next = reduce(
                        recovery,
                        RecoveryEvent.SessionFailed(generation, SystemClock.elapsedRealtime()),
                    )
                    recovery = next.state
                    recovery.step == RecoveryStep.WaitingToReopen
                }
                publishRecovery()
                if (waiting) scheduleReopen()
            }
        }
    }

    private suspend fun publishRecovery() {
        val line = recoveryStatus(recovery)
        val detail = recoveryDetail(recovery)
        val choices = recoveryChoices(recovery)
        ui {
            recoveryLine = line
            recoveryDetail = detail
            recoveryActions = choices
        }
    }

    private suspend fun showRollStatus() {
        if (heartbeat()) return
        ui {
            status = if (files.isEmpty()) "相机里没有照片" else "已显示 ${files.size} / $total 张"
            recoveryLine = null
            recoveryDetail = null
            recoveryActions = null
        }
        resumeSaveIfPaused()
        if (files.size < total) append()
    }

    companion object {
        fun factory(
            roll: CameraRoll,
            gallery: PhoneGallery,
            transfer: TransferFiles,
            onConfirmedCancel: () -> Unit,
            onSessionLost: () -> Unit,
            onSessionText: (String) -> Unit = {},
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PhotoViewModel(roll, gallery, transfer, onConfirmedCancel, onSessionLost, onSessionText) as T
                }
            }
        }
    }
}
