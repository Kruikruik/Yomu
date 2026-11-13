package com.yomu.reader.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.MangaCache
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.ReaderKeys
import com.yomu.reader.data.ReaderMode
import com.yomu.reader.model.Chapter
import com.yomu.reader.model.keyOf
import com.yomu.reader.source.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(sourceId: String, mangaId: String, initialChapterId: String, onBack: () -> Unit) {
    val key = keyOf(sourceId, mangaId)
    val source = remember(sourceId) { SourceManager.get(sourceId) }
    val headers = source?.imageHeaders ?: emptyMap()
    val context = LocalContext.current

    fun startPageFor(id: String): Int =
        Library.progress.value[key]?.takeIf { it.lastChapterId == id && id !in it.read }?.lastPage ?: 0

    var chapterId by rememberSaveable { mutableStateOf(initialChapterId) }
    var currentPage by rememberSaveable { mutableIntStateOf(startPageFor(initialChapterId)) }
    var chapters by remember { mutableStateOf(Library.chaptersFor(key).orEmpty()) }
    var loaded by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(Prefs.modeFor(key)) }
    var showUi by remember { mutableStateOf(false) }
    var dim by remember { mutableFloatStateOf(Prefs.readerDim) }
    var margin by remember { mutableIntStateOf(Prefs.webtoonMargin) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(Prefs.autoScrollSpeed) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    // Pages du chapitre suivant chargées à l'avance (passage instantané).
    val prefetched = remember { mutableMapOf<String, List<String>>() }

    ImmersiveMode()
    DisposableEffect(Unit) {
        ReaderKeys.active = true
        onDispose { ReaderKeys.active = false }
    }

    // Liste des chapitres (si l'appli a été relancée directement sur le lecteur).
    LaunchedEffect(key) {
        if (chapters.isEmpty() && source != null) {
            try {
                val ch = source.chapters(mangaId)
                Library.updateChapters(key, ch)
                chapters = ch
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            }
        }
    }

    // Pages du chapitre courant.
    LaunchedEffect(chapterId, attempt) {
        error = null
        if (loaded?.first == chapterId) return@LaunchedEffect
        if (source == null) {
            error = "Source introuvable"
            return@LaunchedEffect
        }
        try {
            val local = Downloads.localPages(key, chapterId)
            val cached = prefetched[chapterId]
            loaded = chapterId to (local ?: cached ?: source.pages(chapterId))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    val pages = loaded?.takeIf { it.first == chapterId }?.second
    val index = chapters.indexOfFirst { it.id == chapterId }
    val chapter = chapters.getOrNull(index)
    // La liste va du plus récent au plus ancien.
    val nextChapter = if (index > 0) chapters[index - 1] else null
    val prevChapter = if (index >= 0) chapters.getOrNull(index + 1) else null
    val mangaTitle = MangaCache.get(key)?.title ?: Library.get(key)?.manga?.title.orEmpty()

    val goTo: (Chapter) -> Unit = { ch ->
        currentPage = startPageFor(ch.id)
        chapterId = ch.id
        jumpTo = null
    }
    val onPageChanged: (Int) -> Unit = { p ->
        currentPage = p
        pages?.let { Library.saveProgress(key, chapterId, p, it.size) }
    }

    // Précharge les pages suivantes.
    LaunchedEffect(pages, currentPage) {
        val list = pages ?: return@LaunchedEffect
        if (list.firstOrNull()?.startsWith("file://") == true) return@LaunchedEffect
        val end = minOf(currentPage + 4, list.lastIndex)
        for (i in (currentPage + 1)..end) {
            val request = ImageRequest.Builder(context)
                .data(list[i])
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .size(96) // décodage minuscule : le but est juste de remplir le cache disque
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            context.imageLoader.enqueue(request)
        }
    }

    // Près de la fin : prépare le chapitre suivant (liste des pages + premières images).
    LaunchedEffect(pages, currentPage >= (pages?.size ?: Int.MAX_VALUE) - 4, nextChapter?.id) {
        val list = pages ?: return@LaunchedEffect
        val next = nextChapter ?: return@LaunchedEffect
        if (source == null || currentPage < list.size - 4) return@LaunchedEffect
        if (next.id in prefetched || Downloads.isDownloaded(key, next.id)) return@LaunchedEffect
        try {
            val nextPages = source.pages(next.id)
            prefetched[next.id] = nextPages
            nextPages.take(3).forEach { url ->
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                        .size(96) // décodage minuscule : le but est juste de remplir le cache disque
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .build()
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }
    }

    Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when {
                error != null -> CenterMessage("Impossible de charger le chapitre\n$error", "Réessayer") { attempt++ }
                pages == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                pages.isEmpty() -> CenterMessage("Ce chapitre n'a aucune page.")
                else -> key(chapterId, mode) {
                    if (mode == ReaderMode.WEBTOON) {
                        WebtoonReader(
                            pages = pages,
                            headers = headers,
                            initialPage = currentPage,
                            jumpTo = jumpTo,
                            onJumpDone = { jumpTo = null },
                            onTap = { showUi = !showUi },
                            onPageChanged = onPageChanged,
                            nextChapter = nextChapter,
                            onNext = goTo,
                            marginPercent = margin,
                            autoScroll = autoScroll && !showUi,
                            speed = speed,
                            continuous = Prefs.continuousWebtoon,
                        )
                    } else {
                        PagedReader(
                            pages = pages,
                            headers = headers,
                            initialPage = currentPage,
                            rtl = mode == ReaderMode.PAGED_RTL,
                            jumpTo = jumpTo,
                            onJumpDone = { jumpTo = null },
                            onTap = { showUi = !showUi },
                            onPageChanged = onPageChanged,
                            onEndReached = { nextChapter?.let(goTo) },
                        )
                    }
                }
            }

            // Filtre sombre pour lire dans le noir (ne bloque pas les gestes).
            if (dim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.8f)))
                )
            }

            AnimatedVisibility(
                visible = showUi,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopOverlay(
                    mangaTitle = mangaTitle,
                    chapterTitle = chapter?.title.orEmpty(),
                    mode = mode,
                    onBack = onBack,
                    onMode = { m ->
                        mode = m
                        Prefs.setModeFor(key, m)
                    },
                    dim = dim,
                    onDim = {
                        dim = it
                        Prefs.readerDim = it
                    },
                    margin = margin,
                    onMargin = {
                        margin = it
                        Prefs.webtoonMargin = it
                    },
                    autoScroll = autoScroll,
                    onAutoScroll = {
                        autoScroll = it
                        if (it) showUi = false
                    },
                    speed = speed,
                    onSpeed = {
                        speed = it
                        Prefs.autoScrollSpeed = it
                    },
                )
            }

            AnimatedVisibility(
                visible = showUi,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomOverlay(
                    currentPage = currentPage,
                    total = pages?.size ?: 0,
                    rtl = mode == ReaderMode.PAGED_RTL,
                    hasPrev = prevChapter != null,
                    hasNext = nextChapter != null,
                    onPrev = { prevChapter?.let(goTo) },
                    onNext = { nextChapter?.let(goTo) },
                    onSeek = { jumpTo = it },
                )
            }
        }
    }
}

@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

@Composable
private fun TopOverlay(
    mangaTitle: String,
    chapterTitle: String,
    mode: ReaderMode,
    onBack: () -> Unit,
    onMode: (ReaderMode) -> Unit,
    dim: Float,
    onDim: (Float) -> Unit,
    margin: Int,
    onMargin: (Int) -> Unit,
    autoScroll: Boolean,
    onAutoScroll: (Boolean) -> Unit,
    speed: Int,
    onSpeed: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = Color.Black.copy(alpha = 0.85f), contentColor = Color.White) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Column(Modifier.weight(1f)) {
                Text(mangaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    chapterTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Mode de lecture")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ReaderMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label) },
                            onClick = {
                                onMode(m)
                                menu = false
                            },
                            trailingIcon = {
                                if (m == mode) Icon(Icons.Default.Check, contentDescription = null)
                            },
                        )
                    }
                    HorizontalDivider()
                    Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Assombrir l'écran : ${(dim * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium)
                        Slider(value = dim, onValueChange = onDim, valueRange = 0f..0.8f)
                        if (mode == ReaderMode.WEBTOON) {
                            Text("Marges sur les côtés : $margin %", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = margin.toFloat(),
                                onValueChange = { onMargin(it.roundToInt()) },
                                valueRange = 0f..25f,
                            )
                            Text("Vitesse du défilement auto : $speed", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = speed.toFloat(),
                                onValueChange = { onSpeed(it.roundToInt()) },
                                valueRange = 1f..10f,
                                steps = 8,
                            )
                        }
                    }
                    if (mode == ReaderMode.WEBTOON) {
                        DropdownMenuItem(
                            text = { Text(if (autoScroll) "Arrêter le défilement auto" else "Lancer le défilement auto") },
                            onClick = {
                                onAutoScroll(!autoScroll)
                                menu = false
                            },
                            trailingIcon = {
                                if (autoScroll) Icon(Icons.Default.Check, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomOverlay(
    currentPage: Int,
    total: Int,
    rtl: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = 0.85f), contentColor = Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            if (total > 1) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Slider(
                        value = currentPage.coerceIn(0, total - 1).toFloat(),
                        onValueChange = { onSeek(it.roundToInt()) },
                        valueRange = 0f..(total - 1).toFloat(),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrev, enabled = hasPrev) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null)
                    Text("Préc.")
                }
                Text(
                    if (total > 0) "${currentPage + 1} / $total" else "",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onNext, enabled = hasNext) {
                    Text("Suiv.")
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Webtoon

@Composable
private fun WebtoonReader(
    pages: List<String>,
    headers: Map<String, String>,
    initialPage: Int,
    jumpTo: Int?,
    onJumpDone: () -> Unit,
    onTap: () -> Unit,
    onPageChanged: (Int) -> Unit,
    nextChapter: Chapter?,
    onNext: (Chapter) -> Unit,
    marginPercent: Int,
    autoScroll: Boolean,
    speed: Int,
    continuous: Boolean,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceIn(0, pages.lastIndex))
    val nextCallback by rememberUpdatedState(onNext)

    // Touches de volume : avance / recule d'environ un écran.
    LaunchedEffect(state) {
        ReaderKeys.events.collect { dir ->
            val h = state.layoutInfo.viewportSize.height
            state.animateScrollBy(dir * h * 0.85f)
        }
    }

    // Lecture en continu : arrivé tout en bas, on enchaîne sur le chapitre suivant.
    LaunchedEffect(state, nextChapter?.id, continuous) {
        val next = nextChapter ?: return@LaunchedEffect
        if (!continuous) return@LaunchedEffect
        snapshotFlow {
            val info = state.layoutInfo
            val end = info.visibleItemsInfo.lastOrNull { it.index == pages.size }
            end != null && end.offset + end.size / 2 <= info.viewportEndOffset && !state.canScrollForward
        }.distinctUntilChanged().collect { atEnd ->
            if (atEnd) {
                kotlinx.coroutines.delay(700)
                nextCallback(next)
            }
        }
    }
    val sidePadding = (LocalConfiguration.current.screenWidthDp * marginPercent / 100f).dp
    val density = LocalDensity.current

    // Défilement automatique : avance tout seul, en pause quand le menu est affiché.
    LaunchedEffect(autoScroll, speed) {
        if (!autoScroll) return@LaunchedEffect
        val pxPerSecond = with(density) { (40 + speed * 35).dp.toPx() }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            if (!state.isScrollInProgress || dt < 0.1f) state.scrollBy(pxPerSecond * dt.coerceAtMost(0.1f))
        }
    }
    val pageCallback by rememberUpdatedState(onPageChanged)
    val tapCallback by rememberUpdatedState(onTap)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, pages) {
        snapshotFlow {
            val info = state.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@snapshotFlow null
            val last = info.visibleItemsInfo.lastOrNull { it.index == pages.lastIndex }
            if (last != null && last.offset + last.size <= info.viewportEndOffset + 4) {
                pages.lastIndex
            } else {
                state.firstVisibleItemIndex.coerceAtMost(pages.lastIndex)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { pageCallback(it) }
    }

    LaunchedEffect(jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        state.scrollToItem(target.coerceIn(0, pages.lastIndex))
        onJumpDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { width = it.width }
            // Zoom à deux doigts + déplacement horizontal quand on est zoomé.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 3f)
                            val maxX = width * (scale - 1f) / 2f
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxX, maxX)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (scale > 1f) {
                            val maxX = width * (scale - 1f) / 2f
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxX, maxX)
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
            }
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tapCallback() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            offsetX = 0f
                        },
                    )
                },
        ) {
            itemsIndexed(pages, key = { i, _ -> i }, contentType = { _, _ -> "page" }) { _, url ->
                PageImage(
                    url = url,
                    headers = headers,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sidePadding),
                )
            }
            item(key = "end", contentType = "end") {
                ChapterEnd(nextChapter, onNext, continuous)
            }
        }
    }
}

@Composable
private fun ChapterEnd(next: Chapter?, onNext: (Chapter) -> Unit, continuous: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fin du chapitre", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(12.dp))
        if (next != null && continuous) {
            Text("Chapitre suivant…", color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (next != null) {
            Button(onClick = { onNext(next) }) {
                Text("Suivant : ${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("Pas de chapitre suivant pour l'instant.", color = Color.White.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(120.dp))
    }
}

// ------------------------------------------------------------------- Pages

@Composable
private fun PagedReader(
    pages: List<String>,
    headers: Map<String, String>,
    initialPage: Int,
    rtl: Boolean,
    jumpTo: Int?,
    onJumpDone: () -> Unit,
    onTap: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onEndReached: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, pages.lastIndex)) { pages.size }
    val scope = rememberCoroutineScope()
    val pageCallback by rememberUpdatedState(onPageChanged)
    val endCallback by rememberUpdatedState(onEndReached)
    var zoomed by remember { mutableStateOf(false) }

    // Touches de volume : page suivante / précédente.
    LaunchedEffect(pagerState) {
        ReaderKeys.events.collect { dir ->
            val target = pagerState.currentPage + dir
            when {
                target > pages.lastIndex -> endCallback()
                target >= 0 -> pagerState.animateScrollToPage(target)
            }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect {
                zoomed = false
                pageCallback(it)
            }
    }

    LaunchedEffect(jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        pagerState.scrollToPage(target.coerceIn(0, pages.lastIndex))
        onJumpDone()
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = rtl,
        userScrollEnabled = !zoomed,
        beyondViewportPageCount = 2,
        key = { it },
    ) { page ->
        ZoomablePage(
            url = pages[page],
            headers = headers,
            isCurrent = page == pagerState.currentPage,
            onZoomChange = { zoomed = it },
            onTap = { x, width ->
                val third = width / 3f
                val leftZone = x < third
                val rightZone = x > width - third
                val forward = if (rtl) leftZone else rightZone
                val backward = if (rtl) rightZone else leftZone
                val current = pagerState.currentPage
                when {
                    forward && current >= pages.lastIndex -> endCallback()
                    forward -> scope.launch { pagerState.animateScrollToPage(current + 1) }
                    backward && current > 0 -> scope.launch { pagerState.animateScrollToPage(current - 1) }
                    backward -> Unit
                    else -> onTap()
                }
            },
        )
    }
}

@Composable
private fun ZoomablePage(
    url: String,
    headers: Map<String, String>,
    isCurrent: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: (Float, Float) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoomCallback by rememberUpdatedState(onZoomChange)
    val tapCallback by rememberUpdatedState(onTap)

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos -> tapCallback(pos.x, size.width.toFloat()) },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                        zoomCallback(scale > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (newScale > 1f) clamp(offset + event.calculatePan(), newScale) else Offset.Zero
                            if ((newScale > 1f) != (scale > 1f)) zoomCallback(newScale > 1f)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        PageImage(
            url = url,
            headers = headers,
            modifier = Modifier.fillMaxSize(),
            scale = ContentScale.Fit,
            crop = Prefs.cropBorders,
        )
    }
}
