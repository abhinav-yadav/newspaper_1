package com.epaper.reader.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.epaper.reader.data.AppSettings
import com.epaper.reader.data.ReaderLayout
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val INVERT = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
)
private const val MAX_ZOOM = 5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    settings: AppSettings,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenOriginal: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val paper by vm.paper.collectAsState()
    val library by vm.library.collectAsState()
    var chrome by remember { mutableStateOf(true) }
    var showThumbs by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(-1f) }
    var current by remember { mutableIntStateOf(0) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    val dark = settings.readerDark

    // Full screen: hide system bars together with the reader chrome.
    DisposableEffect(chrome) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            (context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(brightness) {
        val window = (context as? Activity)?.window
        window?.attributes = window?.attributes?.apply { screenBrightness = brightness }
        onDispose {
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    val bg = if (dark) Color(0xFF0E0F11) else Color(0xFFE9E6E1)
    Box(Modifier.fillMaxSize().background(bg)) {
        when (val s = state) {
            is ReaderState.Downloading -> CenterMessage(dark) {
                Text(paper?.title ?: "", style = MaterialTheme.typography.titleLarge, color = fg(dark))
                Spacer(Modifier.height(16.dp))
                if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.width(240.dp))
                else LinearProgressIndicator(Modifier.width(240.dp))
                Spacer(Modifier.height(8.dp))
                Text(s.message ?: "Downloading… ${formatBytes(s.bytes)}", color = fg(dark).copy(alpha = .7f))
                if (s.message == "Paused") { Spacer(Modifier.height(12.dp)); Button(onClick = { vm.resume() }) { Text("Resume") } }
            }
            is ReaderState.Failed -> CenterMessage(dark) {
                Text("Couldn't open this paper", style = MaterialTheme.typography.titleLarge, color = fg(dark))
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = fg(dark).copy(alpha = .7f))
                Spacer(Modifier.height(16.dp))
                paper?.let { p -> OutlinedButton(onClick = { onOpenOriginal(p.postUrl) }) { Text("Open original post") } }
            }
            is ReaderState.Ready -> {
                val toggleChrome = { chrome = !chrome }
                if (settings.readerLayout == ReaderLayout.VERTICAL) {
                    VerticalReader(s.doc, s.initialPage, settings.fitPage, dark, jumpTo, onJumped = { jumpTo = null },
                        onTap = toggleChrome, onPage = { current = it; vm.onPage(it) })
                } else {
                    HorizontalReader(s.doc, s.initialPage, settings.fitPage, dark, jumpTo, onJumped = { jumpTo = null },
                        onTap = toggleChrome, onPage = { current = it; vm.onPage(it) })
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = {
                    Column {
                        Text(paper?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        val count = (state as? ReaderState.Ready)?.doc?.pageCount
                        if (count != null) Text("Page ${current + 1} of $count", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.toggleBookmark() }) {
                        Icon(if (library?.bookmarked == true) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark")
                    }
                    if (state is ReaderState.Ready) IconButton(onClick = { showThumbs = true }) { Icon(Icons.Filled.GridView, "Pages") }
                    IconButton(onClick = { showOptions = true }) { Icon(Icons.Filled.Tune, "Reading options") }
                    IconButton(onClick = {
                        paper?.let { p ->
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "${p.title} — source: ${p.postUrl}")
                            context.startActivity(Intent.createChooser(send, "Share source link"))
                        }
                    }) { Icon(Icons.Filled.Share, "Share source link") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bar(dark), titleContentColor = fg(dark),
                    navigationIconContentColor = fg(dark), actionIconContentColor = fg(dark)),
            )
        }

        val ready = state as? ReaderState.Ready
        if (ready != null && ready.doc.pageCount > 1) {
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Surface(color = bar(dark)) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${current + 1}", color = fg(dark), modifier = Modifier.width(32.dp))
                        var drag by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = drag ?: current.toFloat(),
                            onValueChange = { drag = it },
                            onValueChangeFinished = { drag?.let { jumpTo = it.roundToInt() }; drag = null },
                            valueRange = 0f..(ready.doc.pageCount - 1).toFloat(),
                            steps = (ready.doc.pageCount - 2).coerceAtLeast(0),
                            modifier = Modifier.weight(1f),
                        )
                        Text("${ready.doc.pageCount}", color = fg(dark), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    if (showThumbs && state is ReaderState.Ready) {
        val doc = (state as ReaderState.Ready).doc
        ModalBottomSheet(onDismissRequest = { showThumbs = false }) {
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxWidth().height(480.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(doc.pageSizes) { i, (w, h) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { jumpTo = i; showThumbs = false }) {
                        PageImage(doc, i, 220, w.toFloat() / h, dark = false,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium,
                            color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reading options", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.readerLayout == ReaderLayout.VERTICAL, { onSettings { it.copy(readerLayout = ReaderLayout.VERTICAL) } }, { Text("Vertical scroll") })
                    FilterChip(settings.readerLayout == ReaderLayout.HORIZONTAL, { onSettings { it.copy(readerLayout = ReaderLayout.HORIZONTAL) } }, { Text("Page by page") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!settings.fitPage, { onSettings { it.copy(fitPage = false) } }, { Text("Fit width") })
                    FilterChip(settings.fitPage, { onSettings { it.copy(fitPage = true) } }, { Text("Fit page") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark pages", Modifier.weight(1f))
                    Switch(settings.readerDark, { v -> onSettings { it.copy(readerDark = v) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Brightness", Modifier.weight(1f))
                    Text(if (brightness < 0) "System" else "${(brightness * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(value = if (brightness < 0) 0.6f else brightness, onValueChange = { brightness = it.coerceAtLeast(0.02f) })
                OutlinedButton(onClick = { brightness = -1f }) { Text("Use system brightness") }
            }
        }
    }
}

@Composable
private fun CenterMessage(dark: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

private fun fg(dark: Boolean) = if (dark) Color(0xFFE6E6E6) else Color(0xFF1B1B1B)
private fun bar(dark: Boolean) = if (dark) Color(0xEE16181B) else Color(0xEEFBF8F3)

fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000f)
    b >= 1_000 -> "%d KB".format(b / 1_000)
    else -> "$b B"
}

/** Renders a page bitmap at [renderWidth] pixels, showing the previous bitmap until the new one is ready. */
@Composable
private fun PageImage(doc: PdfDocument, page: Int, renderWidth: Int, aspect: Float, dark: Boolean, modifier: Modifier) {
    val bucket = bucketWidth(renderWidth)
    var last by remember(page) { mutableStateOf<Bitmap?>(null) }
    val bmp by produceState(initialValue = doc.cached(page, bucket) ?: last, page, bucket) {
        value = doc.render(page, bucket)?.also { last = it }
    }
    Box(modifier.aspectRatio(aspect).background(Color.White)) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = "Page ${page + 1}", contentScale = ContentScale.FillBounds,
                colorFilter = if (dark) INVERT else null, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Round render sizes so pinch-zooming does not re-render on every frame. */
private fun bucketWidth(w: Int): Int {
    val step = 270
    return (((w + step - 1) / step) * step).coerceAtMost(PdfDocument.MAX_WIDTH)
}

/**
 * Pinch handling that runs before the scrollables see the event, so two-finger gestures zoom
 * and one-finger gestures keep scrolling normally.
 */
private fun Modifier.pinchToZoom(onZoom: (zoom: Float, centroid: Offset) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom, event.calculateCentroid())
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun VerticalReader(
    doc: PdfDocument, initialPage: Int, fitPage: Boolean, dark: Boolean,
    jumpTo: Int?, onJumped: () -> Unit, onTap: () -> Unit, onPage: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    LaunchedEffect(jumpTo) { jumpTo?.let { listState.scrollToItem(it); onJumped() } }
    LaunchedEffect(listState) { snapshotFlow { listState.firstVisibleItemIndex }.collect(onPage) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        Box(
            Modifier.fillMaxSize()
                .pinchToZoom { zoom, centroid ->
                    val old = scale
                    val new = (old * zoom).coerceIn(1f, MAX_ZOOM)
                    val f = new / old
                    if (f == 1f) return@pinchToZoom
                    scale = new
                    // Keep the point under the fingers in place.
                    scope.launch {
                        hScroll.scrollTo(((hScroll.value + centroid.x) * f - centroid.x).roundToInt().coerceAtLeast(0))
                        listState.scrollBy((listState.firstVisibleItemScrollOffset + centroid.y) * (f - 1))
                    }
                }
                .horizontalScroll(hScroll, enabled = scale > 1f)
        ) {
            val contentW = (viewportW * scale).roundToInt()
            LazyColumn(
                state = listState,
                modifier = Modifier.width(with(density) { contentW.toDp() }).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(doc.pageCount, key = { it }) { i ->
                    val (pw, ph) = doc.pageSizes[i]
                    val aspect = pw.toFloat() / ph
                    val pageW = if (fitPage) minOf(contentW.toFloat(), viewportH * scale * aspect) else contentW.toFloat()
                    PageImage(
                        doc, i, pageW.roundToInt(), aspect, dark,
                        Modifier.width(with(density) { pageW.toDp() }).pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onTap() },
                                onDoubleTap = { scale = if (scale > 1.2f) 1f else 2.5f },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HorizontalReader(
    doc: PdfDocument, initialPage: Int, fitPage: Boolean, dark: Boolean,
    jumpTo: Int?, onJumped: () -> Unit, onTap: () -> Unit, onPage: (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = initialPage) { doc.pageCount }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(jumpTo) { jumpTo?.let { pager.scrollToPage(it); onJumped() } }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPage) }

    HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { it }) { i ->
        ZoomablePage(doc, i, fitPage, dark, onTap, onZoomChange = { if (i == pager.currentPage) zoomed = it })
    }
}

@Composable
private fun ZoomablePage(
    doc: PdfDocument, page: Int, fitPage: Boolean, dark: Boolean,
    onTap: () -> Unit, onZoomChange: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    val h = rememberScrollState()
    val v = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(scale) { onZoomChange(scale > 1.01f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (pw, ph) = doc.pageSizes[page]
        val aspect = pw.toFloat() / ph
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val baseW = if (fitPage) minOf(vw, vh * aspect) else vw
        val w = baseW * scale
        Box(
            Modifier.fillMaxSize()
                .pinchToZoom { zoom, c ->
                    val old = scale
                    val new = (old * zoom).coerceIn(1f, MAX_ZOOM)
                    val f = new / old
                    if (f == 1f) return@pinchToZoom
                    scale = new
                    scope.launch {
                        h.scrollTo(((h.value + c.x) * f - c.x).roundToInt().coerceAtLeast(0))
                        v.scrollTo(((v.value + c.y) * f - c.y).roundToInt().coerceAtLeast(0))
                    }
                }
                .verticalScroll(v)
                .horizontalScroll(h, enabled = scale > 1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(with(density) { maxOf(w, vw).toDp() }, with(density) { maxOf(w / aspect, if (fitPage) vh else 0f).toDp() }),
                contentAlignment = Alignment.Center) {
                PageImage(doc, page, w.roundToInt(), aspect, dark,
                    Modifier.width(with(density) { w.toDp() }).pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = if (scale > 1.2f) 1f else 2.5f })
                    })
            }
        }
    }
}
