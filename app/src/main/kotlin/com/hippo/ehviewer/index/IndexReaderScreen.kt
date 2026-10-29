package com.hippo.ehviewer.index

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.ui.Screen
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文样式，随阅读设置变化。 */
private data class ReaderStyle(
    val fontSp: Float,
    val lineHeight: Float,
    val margin: Int,
    val textColor: Color,
    val fontFamily: FontFamily?,
)

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.IndexReaderScreen(
    path: String,
    name: String,
    query: String,
    dir: String,
    size: Long,
    mtime: String,
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    IndexReaderContent(path = path, name = name, query = query, dir = dir, size = size, mtime = mtime, navigator = navigator)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexReaderContent(
    path: String,
    name: String,
    query: String,
    dir: String,
    size: Long,
    mtime: String,
    navigator: DestinationsNavigator,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fontSp by remember { mutableFloatStateOf(IndexReaderPrefs.fontSp(context)) }
    var lineHeight by remember { mutableFloatStateOf(IndexReaderPrefs.lineHeight(context)) }
    var family by remember { mutableIntStateOf(IndexReaderPrefs.family(context)) }
    var margin by remember { mutableIntStateOf(IndexReaderPrefs.margin(context)) }
    var background by remember { mutableIntStateOf(IndexReaderPrefs.background(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(false) }

    val chunks = remember { mutableStateMapOf<Int, List<String>>() }
    val inFlight = remember { mutableSetOf<Int>() }
    var totalLines by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(1) }
    var matches by remember { mutableIntStateOf(0) }
    var firstMatchLine by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var restored by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var fromCache by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val terms = remember(query) {
        query.split(' ', '\n', '\t', '\u3000').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-") }
    }

    suspend fun loadPage(page: Int, initial: Boolean = false) {
        // page == -1 是合法请求：让服务端跳到第一个匹配页。
        if (page < -1) return
        if (chunks.containsKey(page)) return
        val started = synchronized(inFlight) { inFlight.add(page) }
        if (!started) return
        try {
            val result = IndexStore.readPage(context, path, name, dir, query, page)
            chunks[result.page.page] = result.page.lines
            fromCache = result.fromCache
            if (totalLines == 0) totalLines = result.page.totalLines
            totalPages = result.page.totalPages.coerceAtLeast(1)
            if (initial) {
                matches = result.page.matches
                firstMatchLine = result.page.firstMatchLine
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            synchronized(inFlight) { inFlight.remove(page) }
            loadedOnce = true
        }
    }

    LaunchedEffect(path) {
        if (!IndexApi.connected) runCatching { IndexDiscovery.discover(context) }
        favorite = withContext(Dispatchers.IO) { IndexStore.entry(context, path)?.favorite ?: false }
        loadPage(if (query.isNotBlank()) -1 else 0, initial = true)
    }

    LaunchedEffect(totalLines) {
        if (totalLines <= 0 || restored) return@LaunchedEffect
        restored = true
        val saved = withContext(Dispatchers.IO) { IndexStore.entry(context, path)?.lastLine ?: 0 }
        val target = when {
            saved > 0 -> saved
            firstMatchLine >= 0 -> firstMatchLine
            else -> 0
        }
        if (target > 0) listState.scrollToItem(target.coerceIn(0, (totalLines - 1).coerceAtLeast(0)))
    }

    LaunchedEffect(listState, totalLines) {
        if (totalLines <= 0) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            withContext(Dispatchers.IO) {
                IndexStore.touch(context, path, name, dir, size, mtime, index, totalLines)
            }
            val page = index / PAGE_LINES
            for (p in (page - 1).coerceAtLeast(0)..(page + 1).coerceAtMost(totalPages - 1)) {
                loadPage(p)
            }
        }
    }

    val currentLine by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    val scheme = MaterialTheme.colorScheme
    val pageBg = when (background) {
        IndexReaderPrefs.BG_SEPIA -> Color(0xFFF4ECD8)
        IndexReaderPrefs.BG_NIGHT -> Color(0xFF111111)
        IndexReaderPrefs.BG_WHITE -> Color.White
        else -> scheme.background
    }
    val textColor = when (background) {
        IndexReaderPrefs.BG_SEPIA -> Color(0xFF332B1F)
        IndexReaderPrefs.BG_NIGHT -> Color(0xFFC8C8C8)
        IndexReaderPrefs.BG_WHITE -> Color.Black
        else -> scheme.onBackground
    }
    val fontFamily = when (family) {
        IndexReaderPrefs.FAMILY_SERIF -> FontFamily.Serif
        IndexReaderPrefs.FAMILY_MONO -> FontFamily.Monospace
        else -> null
    }
    val style = ReaderStyle(fontSp, lineHeight, margin, textColor, fontFamily)

    Scaffold(
        containerColor = pageBg,
        topBar = {
            AnimatedVisibility(chrome) {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val next = !favorite
                            favorite = next
                            scope.launch(Dispatchers.IO) {
                                IndexStore.touch(context, path, name, dir, size, mtime, listState.firstVisibleItemIndex, totalLines)
                                IndexStore.setFavorite(context, path, next)
                                if (next) runCatching { IndexStore.cacheAll(context, path, name, dir) }
                            }
                        }) {
                            Icon(
                                if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (favorite) "取消收藏" else "收藏",
                            )
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "阅读设置")
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(chrome) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (totalLines > 0) "第 $currentLine / ${"%,d".format(totalLines)} 行" else "读取中…",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (fromCache) {
                            Spacer(Modifier.width(8.dp))
                            Text("离线缓存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.weight(1f))
                        if (matches > 0) {
                            Text("匹配 $matches 处", style = MaterialTheme.typography.labelMedium)
                        }
                        if (firstMatchLine >= 0 && totalLines > 0) {
                            TextButton(onClick = {
                                scope.launch {
                                    listState.scrollToItem(firstMatchLine.coerceIn(0, totalLines - 1))
                                }
                            }) { Text("首个匹配") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(pageBg)
                .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) },
            contentPadding = padding,
        ) {
            error?.let { message ->
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("读取失败：$message", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = {
                            error = null
                            scope.launch { loadPage(if (query.isNotBlank()) -1 else 0, initial = true) }
                        }) {
                            Text("重试")
                        }
                    }
                }
            }
            if (totalLines == 0 && error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        if (loadedOnce) {
                            Text("这个文件没有可显示的内容", color = textColor)
                        } else {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                }
            }
            items(count = totalLines, key = { index -> index }) { index ->
                val lines = chunks[index / PAGE_LINES]
                val text = lines?.getOrNull(index % PAGE_LINES)
                if (text == null) {
                    Spacer(Modifier.height((fontSp * lineHeight).dp))
                } else {
                    LineText(text, terms, style)
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("阅读设置", style = MaterialTheme.typography.titleMedium)
                Text("字号 ${fontSp.toInt()} sp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = fontSp,
                    onValueChange = { fontSp = it; IndexReaderPrefs.setFontSp(context, it) },
                    valueRange = 11f..30f,
                )
                Text("行距 ${"%.1f".format(lineHeight)}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = lineHeight,
                    onValueChange = { lineHeight = it; IndexReaderPrefs.setLineHeight(context, it) },
                    valueRange = 1.2f..2.4f,
                )
                Text("字体", style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    Chip("系统", family == IndexReaderPrefs.FAMILY_SYSTEM) {
                        family = IndexReaderPrefs.FAMILY_SYSTEM; IndexReaderPrefs.setFamily(context, family)
                    }
                    Chip("衬线", family == IndexReaderPrefs.FAMILY_SERIF) {
                        family = IndexReaderPrefs.FAMILY_SERIF; IndexReaderPrefs.setFamily(context, family)
                    }
                    Chip("等宽", family == IndexReaderPrefs.FAMILY_MONO) {
                        family = IndexReaderPrefs.FAMILY_MONO; IndexReaderPrefs.setFamily(context, family)
                    }
                }
                Text("页边距", style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    listOf(8, 16, 24, 40).forEach { value ->
                        Chip("${value}dp", margin == value) {
                            margin = value; IndexReaderPrefs.setMargin(context, value)
                        }
                    }
                }
                Text("背景", style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    Chip("跟随系统", background == IndexReaderPrefs.BG_SYSTEM) {
                        background = IndexReaderPrefs.BG_SYSTEM; IndexReaderPrefs.setBackground(context, background)
                    }
                    Chip("护眼", background == IndexReaderPrefs.BG_SEPIA) {
                        background = IndexReaderPrefs.BG_SEPIA; IndexReaderPrefs.setBackground(context, background)
                    }
                    Chip("夜间", background == IndexReaderPrefs.BG_NIGHT) {
                        background = IndexReaderPrefs.BG_NIGHT; IndexReaderPrefs.setBackground(context, background)
                    }
                    Chip("纯白", background == IndexReaderPrefs.BG_WHITE) {
                        background = IndexReaderPrefs.BG_WHITE; IndexReaderPrefs.setBackground(context, background)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun LineText(raw: String, terms: List<String>, style: ReaderStyle) {
    if (raw.isEmpty()) {
        Spacer(Modifier.height((style.fontSp * style.lineHeight * 0.55f).dp))
        return
    }
    Text(
        text = if (terms.isEmpty()) AnnotatedString(raw) else highlight(raw, terms),
        color = style.textColor,
        fontFamily = style.fontFamily,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = style.fontSp.sp,
            lineHeight = (style.fontSp * style.lineHeight).sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = style.margin.dp, vertical = 1.dp),
    )
}

private fun highlight(line: String, terms: List<String>): AnnotatedString = buildAnnotatedString {
    append(line)
    val lower = line.lowercase()
    val background = Color(0x66FFD54F)
    for (term in terms) {
        val needle = term.lowercase()
        if (needle.isEmpty()) continue
        var from = 0
        while (true) {
            val at = lower.indexOf(needle, from)
            if (at < 0) break
            addStyle(SpanStyle(background = background), at, at + needle.length)
            from = at + needle.length
        }
    }
}
