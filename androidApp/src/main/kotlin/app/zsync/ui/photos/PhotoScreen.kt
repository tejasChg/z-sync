package app.zsync.ui.photos

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.zsync.gallery.CameraFile
import app.zsync.gallery.CatalogRow
import app.zsync.gallery.EMPTY_FILTER
import app.zsync.gallery.GridCount
import app.zsync.gallery.MediaFilter
import app.zsync.gallery.OPEN_WITH_SYSTEM
import app.zsync.gallery.PlaybackKind
import app.zsync.gallery.VIDEO_NEEDS_SYNC
import app.zsync.gallery.VIDEO_OPEN_FAILED
import app.zsync.gallery.VIDEO_PLACEHOLDER
import app.zsync.gallery.catalogRows
import app.zsync.gallery.groupByCameraDate
import app.zsync.gallery.mediaPosition
import app.zsync.gallery.playbackKind
import app.zsync.gallery.videoMime
import app.zsync.gallery.visibleMedia
import app.zsync.gallery.PhotoFormat
import app.zsync.gallery.PhotoLayout
import app.zsync.gallery.PhotoOrder
import app.zsync.gallery.showTailSpinner
import app.zsync.gallery.formatBytes
import app.zsync.ptp.ShotInfo
import app.zsync.link.BatteryExemption
import app.zsync.ui.components.BatteryHintCard
import app.zsync.ui.components.ConfirmDialog
import app.zsync.ui.theme.Danger
import app.zsync.ui.theme.OnDanger
import app.zsync.ui.components.ZSyncToast
import app.zsync.ui.theme.Card
import app.zsync.ui.theme.PageMargin
import app.zsync.ui.theme.PageTopGap
import app.zsync.ui.theme.CardSelected
import app.zsync.ui.theme.Ink
import app.zsync.ui.theme.Line
import app.zsync.ui.theme.Muted
import app.zsync.ui.theme.Phone
import app.zsync.ui.theme.Stone
import app.zsync.ui.theme.StoneInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PhotoRoute(internetAvailable: Boolean, model: PhotoViewModel) {
    LaunchedEffect(Unit) { model.start() }
    val context = LocalContext.current
    val vendorRom = remember { BatteryExemption.isOplusRom() }
    var exempt by remember { mutableStateOf(BatteryExemption.isExempt(context)) }
    var hintDismissed by remember { mutableStateOf(false) }
    // Coming back from the system dialog must re-read the flag, or the card would stay for good.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryExemption.isExempt(context) }
    // Thumbnails stop while the app is off screen: that background burst is what heated the CPU.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.setForeground(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.setForeground(false) }
    var pendingDelete by remember { mutableStateOf<Set<Long>?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    var downloadPreview by remember { mutableStateOf<Long?>(null) }
    var pendingGalleryDelete by remember { mutableStateOf<Long?>(null) }
    val savedCount = model.saves.count { it.label == "已保存" }
    if (showDownloads || downloadPreview != null) {
        BackHandler {
            when {
                pendingGalleryDelete != null -> pendingGalleryDelete = null
                downloadPreview != null -> downloadPreview = null
                else -> showDownloads = false
            }
        }
    }
    if (downloadPreview != null) {
        val row = model.saves.find { it.handle == downloadPreview }
        LaunchedEffect(row, downloadPreview) {
            if (downloadPreview != null && row == null) downloadPreview = null
        }
        if (row != null) {
            DownloadPreview(
                row = row,
                // A RAW cannot be decoded, so fall back to the camera thumbnail the grid already has
                // instead of leaving an empty box above the explanation.
                thumb = model.thumbs[row.handle],
                original = if (model.savedPreviewHandle == row.handle) model.savedPreview else null,
                opening = model.savedPreviewHandle == row.handle && model.savedPreviewStatus == "正在打开原图",
                status = if (model.savedPreviewHandle == row.handle) model.savedPreviewStatus else null,
                onBack = { downloadPreview = null },
                onAskDelete = { pendingGalleryDelete = row.handle },
                onProcess = { watermarkText, style ->
                    model.processSaved(row.handle, watermarkText, style, 1.0f)
                },
                processStatus = model.processStatus,
            )
        }
    } else if (showDownloads) {
        DownloadPage(
            saves = model.saves,
            thumbs = model.thumbs,
            savedCount = savedCount,
            onBack = { showDownloads = false },
            onOpen = {
                model.showSaved(it)
                downloadPreview = it
            },
        )
    } else {
        PhotoScreen(
            internetAvailable = internetAvailable,
            layout = model.layout,
            count = model.count,
            files = model.files,
            thumbs = model.thumbs,
            status = model.status,
            total = model.total,
            loadingMore = model.loadingMore,
            tailSpinner = showTailSpinner(model.firstVisible, model.lastVisible, model.files.size, model.total) &&
                model.loadingMore,
            onScrolledTo = model::onScrolledTo,
            selecting = model.selecting,
            selected = model.selected,
            previewHandle = model.previewHandle,
            notice = model.notice,
            saves = model.saves,
            savedCount = savedCount,
            order = model.order,
            filter = model.filter,
            fullHandle = model.fullHandle,
            fullImage = model.fullImage,
            fullStatus = model.fullStatus,
            shots = model.shots,
            onLayout = { model.layout = it },
            onOrder = model::chooseOrder,
            onFilter = model::chooseFilter,
            findVideo = model::findVideo,
            onCount = { model.count = it },
            onToggleSelectMode = { model.changeSelecting(!model.selecting) },
            onOpen = model::open,
            onPreview = model::show,
            onClosePreview = { model.previewHandle = null },
            onAskDelete = { pendingDelete = it },
            onSync = model::saveSelected,
            onDismissNotice = model::clearNotice,
            onOpenDownloads = { showDownloads = true },
            recoveryLine = model.recoveryLine,
            recoveryDetail = model.recoveryDetail,
            recoveryActions = model.recoveryActions,
            onContinueRecovery = model::continueRecovery,
            onCancelRecovery = model::cancelRecovery,
            loadFailed = model.loadFailed,
            onLeaveBroken = model::leaveBrokenSession,
            backgroundHint = !hintDismissed && (vendorRom || !exempt),
            onAllowBackground = {
                // ColorOS's freezer ignores the Doze allowlist, so point at the vendor toggle there.
                if (vendorRom) BatteryExemption.openAppSettings(context) else BatteryExemption.request(context)
            },
            onDismissBackground = { hintDismissed = true },
        )
    }
    pendingDelete?.let { handles ->
        ConfirmDialog(
            title = if (handles.size == 1) "从相机删除这张照片？" else "从相机删除这 ${handles.size} 张照片？",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                model.confirmDelete(handles)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
    pendingGalleryDelete?.let { handle ->
        ConfirmDialog(
            title = "从手机相册删除这张照片？",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                model.deleteSaved(handle)
                pendingGalleryDelete = null
                downloadPreview = null
            },
            onDismiss = { pendingGalleryDelete = null },
        )
    }
}

@Composable
private fun PhotoScreen(
    internetAvailable: Boolean,
    layout: PhotoLayout,
    count: GridCount,
    files: List<CameraFile>,
    thumbs: Map<Long, ImageBitmap>,
    status: String,
    total: Int,
    loadingMore: Boolean,
    tailSpinner: Boolean,
    onScrolledTo: (Int, Int) -> Unit,
    selecting: Boolean,
    selected: Set<Long>,
    previewHandle: Long?,
    notice: String?,
    saves: List<SaveProgress>,
    savedCount: Int,
    order: PhotoOrder,
    filter: MediaFilter,
    fullHandle: Long?,
    fullImage: ImageBitmap?,
    fullStatus: String?,
    shots: Map<Long, ShotInfo>,
    onLayout: (PhotoLayout) -> Unit,
    onOrder: (PhotoOrder) -> Unit,
    onFilter: (MediaFilter) -> Unit,
    findVideo: suspend (CameraFile) -> Uri?,
    onCount: (GridCount) -> Unit,
    onToggleSelectMode: () -> Unit,
    onOpen: (Long) -> Unit,
    onPreview: (Long) -> Unit,
    onClosePreview: () -> Unit,
    onAskDelete: (Set<Long>) -> Unit,
    onSync: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenDownloads: () -> Unit,
    recoveryLine: String? = null,
    recoveryDetail: String? = null,
    recoveryActions: Pair<String, String>? = null,
    onContinueRecovery: () -> Unit = {},
    onCancelRecovery: () -> Unit = {},
    loadFailed: Boolean = false,
    onLeaveBroken: () -> Unit = {},
    backgroundHint: Boolean = false,
    onAllowBackground: () -> Unit = {},
    onDismissBackground: () -> Unit = {},
) {
    val visible = visibleMedia(files, filter)
    val rows = catalogRows(groupByCameraDate(visible, order))
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Phone)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PageMargin, end = PageMargin, top = PageTopGap, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("照片", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Text(status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (internetAvailable) "已连接 · 流量正常" else "已连接 · 外网中断",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Text(
                    if (saves.isEmpty()) "下载" else "下载 · 本次 $savedCount 张",
                    color = Ink,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClick = onOpenDownloads),
                )
            }
        }
        recoveryLine?.let { line ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Card, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Stone, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(line, color = Ink, fontSize = 15.sp)
                        recoveryDetail?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    }
                }
                recoveryActions?.let { (stay, leave) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancelRecovery) { Text(leave, color = Ink) }
                        Text(
                            stay,
                            color = StoneInk,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .background(Stone, RoundedCornerShape(14.dp))
                                .clickable(onClick = onContinueRecovery)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        if (backgroundHint) {
            BatteryHintCard(
                onAllow = onAllowBackground,
                onDismiss = onDismissBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (status.startsWith("正在")) {
            val fraction = if (total > 0 && status.startsWith("正在加载预览")) {
                files.size.toFloat() / total.toFloat()
            } else {
                null
            }
            if (fraction == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(4.dp),
                    color = Stone,
                    trackColor = Line,
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(4.dp),
                    color = Stone,
                    trackColor = Line,
                )
            }
        }
        notice?.let { message ->
            ZSyncToast(message, onDismiss = onDismissNotice)
        }
        val saving = saves.any { it.label != "已保存" && it.label != "已跳过" && it.label != "失败" }
        if (saving) {
            val index = currentSaveIndex(saves)
            val row = saves[index]
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${index + 1}/${saves.size}", color = Ink, fontSize = 22.sp)
                    Text(
                        if (row.label == "已保存" || row.label == "已跳过" || row.label == "已下载") "100%" else row.label,
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
                Text(row.name, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                LinearProgressIndicator(
                    progress = { row.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp),
                    color = Stone,
                    trackColor = Line,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("已保存 $savedCount 张", color = Ink, fontSize = 13.sp)
                    Text("还剩 ${saves.size - savedCount} 张", color = Ink, fontSize = 13.sp)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segmented(
                options = listOf(PhotoLayout.GRID to "大图", PhotoLayout.LIST to "列表"),
                selected = layout,
                onSelect = onLayout,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (order == PhotoOrder.OLDEST) "最早" else "最新",
                    color = Ink,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable {
                            onOrder(if (order == PhotoOrder.OLDEST) PhotoOrder.NEWEST else PhotoOrder.OLDEST)
                        }
                        .padding(6.dp),
                )
                Text(
                    if (selecting) "完成" else "选择",
                    color = Ink,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onToggleSelectMode).padding(6.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MediaFilter.entries.forEach { option ->
                val on = option == filter
                Text(
                    option.label,
                    color = if (on) Ink else Muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(if (on) CardSelected else Card, RoundedCornerShape(11.dp))
                        .clickable { onFilter(option) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (layout == PhotoLayout.GRID) {
            Row(
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("一屏", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Segmented(
                    options = GridCount.entries.map { it to "${it.columns * it.rows} 张" },
                    selected = count,
                    onSelect = onCount,
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (layout == PhotoLayout.GRID) {
                PhotoGrid(rows, thumbs, count, order, filter, selecting, selected, onOpen, onScrolledTo)
            } else {
                PhotoList(rows, thumbs, order, filter, selecting, selected, onOpen, onScrolledTo)
            }
            if (files.isEmpty() && loadingMore) {
                CircularProgressIndicator(
                    color = Stone,
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            } else if (visible.isEmpty() && files.isNotEmpty()) {
                Text(
                    EMPTY_FILTER,
                    color = Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else if (files.isEmpty() && loadFailed) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(status, color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Text(
                        "返回连接页",
                        color = Ink,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .border(1.dp, Line, RoundedCornerShape(12.dp))
                            .clickable(onClick = onLeaveBroken)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            } else if (files.isEmpty()) {
                Text(
                    status,
                    color = Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            if (tailSpinner) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Phone.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Stone, strokeWidth = 2.dp, modifier = Modifier.size(48.dp))
                }
            }
        }
        if (selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ${selected.size} 张", color = Muted, fontSize = 13.sp)
                Row {
                    ActionButton("删除", enabled = selected.isNotEmpty(), danger = true) {
                        onAskDelete(selected)
                    }
                    if (selected.isNotEmpty()) {
                        ActionButton("同步到相册", enabled = true, danger = false, onClick = onSync)
                    }
                }
            }
        }
    }
    if (previewHandle != null && visible.isNotEmpty()) {
        Preview(
            files = visible,
            findVideo = findVideo,
            thumbs = thumbs,
            startHandle = previewHandle,
            fullHandle = fullHandle,
            fullImage = fullImage,
            fullStatus = fullStatus,
            shots = shots,
            onClose = onClosePreview,
            onDelete = { onAskDelete(setOf(it)) },
            onPage = onPreview,
        )
    }
}

@Composable
private fun PhotoGrid(
    rows: List<CatalogRow>,
    thumbs: Map<Long, ImageBitmap>,
    count: GridCount,
    order: PhotoOrder,
    filter: MediaFilter,
    selecting: Boolean,
    selected: Set<Long>,
    onOpen: (Long) -> Unit,
    onScrolledTo: (Int, Int) -> Unit,
) {
    val state = remember(order, filter) { LazyGridState() }
    LaunchedEffect(state, rows) {
        snapshotFlow {
            val shown = state.layoutInfo.visibleItemsInfo
            mediaPosition(rows, shown.firstOrNull()?.index ?: 0) to
                mediaPosition(rows, shown.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .collect { (first, last) -> onScrolledTo(first, last) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        val gap = 6.dp
        val tileHeight = (maxHeight - gap * (count.rows - 1)) / count.rows
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(count.columns),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(
                items = rows,
                key = { row -> rowKey(row) },
                span = { row -> GridItemSpan(if (row is CatalogRow.Heading) count.columns else 1) },
            ) { row ->
                when (row) {
                    is CatalogRow.Heading -> DateHeading(row.text)
                    is CatalogRow.Media -> Tile(
                        file = row.file,
                        thumb = thumbs[row.file.handle],
                        selected = row.file.handle in selected,
                        showMark = selecting,
                        modifier = Modifier.height(tileHeight),
                        onClick = { onOpen(row.file.handle) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoList(
    rows: List<CatalogRow>,
    thumbs: Map<Long, ImageBitmap>,
    order: PhotoOrder,
    filter: MediaFilter,
    selecting: Boolean,
    selected: Set<Long>,
    onOpen: (Long) -> Unit,
    onScrolledTo: (Int, Int) -> Unit,
) {
    val state = remember(order, filter) { LazyListState() }
    LaunchedEffect(state, rows) {
        snapshotFlow {
            val shown = state.layoutInfo.visibleItemsInfo
            mediaPosition(rows, shown.firstOrNull()?.index ?: 0) to
                mediaPosition(rows, shown.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .collect { (first, last) -> onScrolledTo(first, last) }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { row -> rowKey(row) }) { row ->
            when (row) {
                is CatalogRow.Heading -> DateHeading(row.text)
                is CatalogRow.Media -> {
                    val file = row.file
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(file.handle) }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tile(
                            file,
                            thumbs[file.handle],
                            file.handle in selected,
                            selecting,
                            Modifier.width(72.dp).height(72.dp),
                        ) { onOpen(file.handle) }
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(file.name, color = Ink, fontSize = 15.sp)
                            Text(formatBytes(file.bytes), color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun rowKey(row: CatalogRow): Any = when (row) {
    is CatalogRow.Heading -> "date:${row.key ?: "none"}"
    is CatalogRow.Media -> row.file.handle
}

@Composable
private fun DateHeading(text: String) {
    Text(
        text,
        color = Ink,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun Tile(
    file: CameraFile,
    thumb: ImageBitmap?,
    selected: Boolean,
    showMark: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (thumb == null) Card else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        if (thumb != null) {
            Image(thumb, contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (file.format == PhotoFormat.MOV || file.format == PhotoFormat.MP4) {
            Text(VIDEO_PLACEHOLDER, color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            formatBytes(file.bytes),
            color = Ink,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
        if (showMark) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) Stone else Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, Color.White, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = StoneInk,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Preview(
    files: List<CameraFile>,
    findVideo: suspend (CameraFile) -> Uri?,
    thumbs: Map<Long, ImageBitmap>,
    startHandle: Long,
    fullHandle: Long?,
    fullImage: ImageBitmap?,
    fullStatus: String?,
    shots: Map<Long, ShotInfo>,
    onClose: () -> Unit,
    onDelete: (Long) -> Unit,
    onPage: (Long) -> Unit,
) {
    val start = files.indexOfFirst { it.handle == startHandle }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start, pageCount = { files.size })
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 120.dp.toPx() }
    val fade = (1f - abs(dragY.value) / (dismissPx * 3f)).coerceIn(0.4f, 1f)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    LaunchedEffect(pager.currentPage) {
        scale = 1f
        offset = Offset.Zero
        rotation = 0f
        dragY.snapTo(0f)
        files.getOrNull(pager.currentPage)?.let { onPage(it.handle) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = fade)),
    ) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = scale <= 1.05f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + if (scale <= 1.05f) dragY.value else 0f
                }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0
                        var accX = 0f
                        var accY = 0f
                        var pull = 0f
                        var longPressed = false
                        var upTime = down.uptimeMillis
                        val hold = scope.launch {
                            delay(longPressMs)
                            if (mode == 0) {
                                mode = 4
                                longPressed = true
                                showInfo = true
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val twist = event.calculateRotation()
                                val pan = event.calculatePan()
                                val pointers = event.changes.count { it.pressed }
                                upTime = event.changes.first().uptimeMillis
                                if (mode == 0) {
                                    accX += pan.x
                                    accY += pan.y
                                    mode = when {
                                        pointers >= 2 || abs(zoom - 1f) > 0.02f || abs(twist) > 0.6f -> 1
                                        scale > 1.05f && (abs(accX) > slop || abs(accY) > slop) -> 1
                                        abs(accY) > slop && abs(accY) > abs(accX) && scale <= 1.05f -> 2
                                        abs(accX) > slop && abs(accX) >= abs(accY) -> 3
                                        else -> 0
                                    }
                                    if (mode != 0) hold.cancel()
                                }
                                if (mode == 1) {
                                    val next = (scale * zoom).coerceIn(1f, 4f)
                                    val raw = if (next <= 1f) Offset.Zero else offset + pan
                                    val maxX = size.width * (next - 1f) / 2f
                                    val maxY = size.height * (next - 1f) / 2f
                                    scale = next
                                    offset = Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
                                    rotation += twist
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (mode == 2) {
                                    pull += pan.y
                                    val shown = pull
                                    scope.launch { dragY.snapTo(shown) }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (mode == 4) {
                                    event.changes.forEach { it.consume() }
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                        } finally {
                            hold.cancel()
                        }
                        if (longPressed) {
                            showInfo = false
                        } else if (mode == 0 && abs(accX) < slop && abs(accY) < slop) {
                            if (upTime - lastTap < 300) {
                                scale = 1f
                                offset = Offset.Zero
                                lastTap = 0
                            } else {
                                lastTap = upTime
                            }
                        }
                        if (mode == 1) {
                            rotation = kotlin.math.round(rotation / 90f) * 90f
                        }
                        if (mode == 2) {
                            if (abs(pull) > dismissPx) onClose() else scope.launch { dragY.animateTo(0f) }
                        }
                    }
                },
        ) { page ->
            val file = files[page]
            if (file.format == PhotoFormat.MOV || file.format == PhotoFormat.MP4) {
                VideoPage(file = file, poster = thumbs[file.handle], findVideo = findVideo)
                return@HorizontalPager
            }
            val shown = if (file.handle == fullHandle && fullImage != null) fullImage else thumbs[file.handle]
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (shown != null) {
                    Image(
                        shown,
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            rotationZ = rotation
                            val fit = rotatedFitScale(
                                bitmapW = shown.width.toFloat(),
                                bitmapH = shown.height.toFloat(),
                                viewW = size.width,
                                viewH = size.height,
                                degrees = rotation,
                            )
                            scaleX = fit
                            scaleY = fit
                        },
                        contentScale = ContentScale.Fit,
                    )
                }
                if (file.handle == fullHandle && fullImage == null && fullStatus == "正在下载原片") {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
                }
            }
        }
        if (showInfo) {
            val file = files[pager.currentPage]
            val shot = shots[file.handle]
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(file.name, color = Color.White, fontSize = 15.sp)
                Text(formatBytes(file.bytes), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                shot?.aperture?.let { Text("光圈 $it", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                shot?.lens?.let { Text("镜头 $it", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                shot?.exif?.let { Text("EXIF $it", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        Text(
            "旋转",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { rotation += 90f }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewAction(
                label = "关闭",
                icon = Icons.Rounded.Close,
                tint = Ink,
                background = Card.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
                onClick = onClose,
            )
            PreviewAction(
                label = "删除",
                icon = Icons.Rounded.Delete,
                tint = OnDanger,
                background = Danger,
                modifier = Modifier.weight(1f),
                onClick = { onDelete(files[pager.currentPage].handle) },
            )
        }
    }
}

@Composable
private fun VideoPage(
    file: CameraFile,
    poster: ImageBitmap?,
    findVideo: suspend (CameraFile) -> Uri?,
) {
    var saved by remember(file.handle) { mutableStateOf<Uri?>(null) }
    var looked by remember(file.handle) { mutableStateOf(false) }
    var inAppFailed by remember(file.handle) { mutableStateOf(false) }
    var systemFailed by remember(file.handle) { mutableStateOf(false) }
    LaunchedEffect(file.handle) {
        saved = withContext(Dispatchers.IO) { findVideo(file) }
        looked = true
    }
    val kind = if (!looked) null else playbackKind(file.format, saved != null, inAppFailed, systemFailed)
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (poster != null && kind != PlaybackKind.InApp) {
            Image(poster, contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        when (kind) {
            null -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
            PlaybackKind.NeedsSync -> Text(VIDEO_NEEDS_SYNC, color = Color.White, fontSize = 15.sp)
            PlaybackKind.Failed -> Text(VIDEO_OPEN_FAILED, color = Color.White, fontSize = 15.sp)
            PlaybackKind.System -> Text(
                OPEN_WITH_SYSTEM,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable {
                        val uri = saved ?: return@clickable
                        val mime = videoMime(file.format) ?: "video/*"
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            systemFailed = true
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            PlaybackKind.InApp -> AndroidView(
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        setVideoURI(saved)
                        setOnErrorListener { _, _, _ ->
                            inAppFailed = true
                            true
                        }
                        setOnPreparedListener { player -> player.start() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            PlaybackKind.Photo -> Unit
        }
    }
}

@Composable
private fun PreviewAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    background: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(background)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            color = tint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.background(Card, RoundedCornerShape(14.dp)).padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                color = if (on) Ink else Muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(if (on) CardSelected else Color.Transparent, RoundedCornerShape(11.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, danger: Boolean, onClick: () -> Unit) {
    val fill = if (danger) Danger else Stone
    val ink = if (danger) OnDanger else StoneInk
    Text(
        label,
        color = if (enabled) ink else Muted,
        fontSize = 14.sp,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(if (enabled) fill else fill.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

private fun activeSaveIndex(saves: List<SaveProgress>): Int {
    val active = saves.indexOfFirst { it.label != "已保存" && it.label != "已跳过" && it.label != "失败" }
    return if (active >= 0) active else saves.lastIndex.coerceAtLeast(0)
}

/**
 * The row the progress card follows. While files are still downloading it must be the file whose
 * chunks are arriving, not the first unfinished one, otherwise the card sits on a finished download
 * while the next file is already transferring.
 */
private fun currentSaveIndex(saves: List<SaveProgress>): Int {
    val downloading = saves.indexOfFirst { it.label.endsWith("%") }
    return if (downloading >= 0) downloading else activeSaveIndex(saves)
}

private fun downloadDone(label: String) = label == "已保存" || label == "已跳过"

private fun downloadOutcome(label: String) = when (label) {
    "已保存" -> "已存进相册"
    "已跳过" -> "已在相册"
    "失败" -> "没有保存成功"
    "等待" -> "还没开始"
    "保存中" -> "正在保存"
    "已下载" -> "下载完成"
    else -> "还没下完"
}

@Composable
private fun DownloadPage(
    saves: List<SaveProgress>,
    thumbs: Map<Long, ImageBitmap>,
    savedCount: Int,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val running = saves.any { it.label != "失败" && !downloadDone(it.label) }
    val index = if (saves.isEmpty()) 0 else currentSaveIndex(saves)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Phone)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Text(
            "返回",
            color = Ink,
            fontSize = 15.sp,
            modifier = Modifier
                .padding(start = PageMargin, end = PageMargin, top = PageTopGap, bottom = 8.dp)
                .clickable(onClick = onBack),
        )
        Text("本次下载", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            when {
                saves.isEmpty() -> "还没开始"
                running -> "正在保存 ${index + 1}/${saves.size} · ${saves[index].name}"
                else -> "这一次已经结束，$savedCount/${saves.size}"
            },
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Text(
            "$savedCount",
            color = Ink,
            fontSize = 42.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            if (saves.isEmpty()) "张已存进相册" else "张已存进相册，还剩 ${saves.size - savedCount} 张",
            color = Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            items(saves, key = { it.handle }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(row.handle) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DownloadThumb(row.handle, thumbs[row.handle], 48.dp, downloadDone(row.label))
                    Text(row.name, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Text(downloadOutcome(row.label), color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun DownloadPreview(
    row: SaveProgress,
    thumb: ImageBitmap? = null,
    original: ImageBitmap?,
    opening: Boolean,
    status: String?,
    onBack: () -> Unit,
    onAskDelete: () -> Unit,
    onProcess: (watermarkText: String, style: String) -> Unit,
    processStatus: String? = null,
) {
    var showProcessDialog by remember { mutableStateOf(false) }
    var watermarkText by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf("sharp") }
    var styleMenuOpen by remember { mutableStateOf(false) }
    val canProcess = row.name.endsWith(".JPG", ignoreCase = true) ||
        row.name.endsWith(".JPEG", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Phone)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Text(
            "返回",
            color = Ink,
            fontSize = 15.sp,
            modifier = Modifier
                .padding(start = PageMargin, end = PageMargin, top = PageTopGap, bottom = 8.dp)
                .clickable(onClick = onBack),
        )
        when {
            original != null -> Image(
                original,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(460.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit,
            )
            opening -> Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(460.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Stone, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
            }
            thumb != null -> Image(
                thumb,
                contentDescription = row.name,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(460.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .alpha(0.7f),
                contentScale = ContentScale.Fit,
            )
        }
        Text(row.name, color = Ink, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
        Text(
            status ?: downloadOutcome(row.label),
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (canProcess) {
            Text(
                "处理此图",
                color = Ink,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .clickable {
                        watermarkText = ""
                        selectedStyle = "sharp"
                        styleMenuOpen = false
                        showProcessDialog = true
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        processStatus?.let { message ->
            Text(
                message,
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Text(
            "从相册删除",
            color = Ink,
            fontSize = 14.sp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .clickable(onClick = onAskDelete)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    if (showProcessDialog) {
        AlertDialog(
            onDismissRequest = {
                showProcessDialog = false
                styleMenuOpen = false
            },
            title = { Text("处理此图", color = Ink) },
            text = {
                Column {
                    OutlinedTextField(
                        value = watermarkText,
                        onValueChange = { watermarkText = it },
                        label = { Text("水印文字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "滤镜风格",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Box {
                        Text(
                            selectedStyle,
                            color = Ink,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { styleMenuOpen = true }
                                .padding(vertical = 10.dp),
                        )
                        DropdownMenu(
                            expanded = styleMenuOpen,
                            onDismissRequest = { styleMenuOpen = false },
                        ) {
                            listOf("sharp", "smooth").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        selectedStyle = option
                                        styleMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showProcessDialog = false
                        styleMenuOpen = false
                        onProcess(watermarkText, selectedStyle)
                    },
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showProcessDialog = false
                        styleMenuOpen = false
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun DownloadThumb(
    handle: Long,
    thumb: ImageBitmap?,
    size: androidx.compose.ui.unit.Dp,
    done: Boolean,
    modifier: Modifier = Modifier,
) {
    val frame = modifier
        .height(size)
        .then(if (modifier == Modifier) Modifier.width(size) else Modifier)
        .clip(RoundedCornerShape(if (size < 80.dp) 8.dp else 16.dp))
        .background(swatch(handle))
        .alpha(if (done) 1f else 0.4f)
    if (thumb == null) {
        Box(frame)
    } else {
        Image(thumb, contentDescription = null, modifier = frame, contentScale = ContentScale.Crop)
    }
}

private fun rotatedFitScale(
    bitmapW: Float,
    bitmapH: Float,
    viewW: Float,
    viewH: Float,
    degrees: Float,
): Float {
    if (bitmapW <= 0f || bitmapH <= 0f || viewW <= 0f || viewH <= 0f) return 1f
    val fit = min(viewW / bitmapW, viewH / bitmapH)
    val drawnW = bitmapW * fit
    val drawnH = bitmapH * fit
    val radians = Math.toRadians(degrees.toDouble())
    val across = abs(cos(radians)).toFloat()
    val upright = abs(sin(radians)).toFloat()
    val boundsW = drawnW * across + drawnH * upright
    val boundsH = drawnW * upright + drawnH * across
    if (boundsW <= 0f || boundsH <= 0f) return 1f
    return min(viewW / boundsW, viewH / boundsH)
}

private fun swatch(handle: Long): Color = swatches[(handle.toInt() - 1).floorMod(swatches.size)]

private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

private val swatches = listOf(
    Color(0xFF6E6256),
    Color(0xFF8D7358),
    Color(0xFF3E4650),
    Color(0xFFA08A72),
    Color(0xFF5C534C),
    Color(0xFF7A8490),
)

