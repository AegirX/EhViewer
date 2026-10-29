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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.IndexReaderScreen(
    path: String,
    name: String,
    query: String,
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    IndexReaderContent(path = path, name = name, query = query, navigator = navigator)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexReaderContent(path: String, name: String, query: String, navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val prefs = remember { IndexDiscovery.prefs(context) }
    val posKey = remember(path) { "pos_" + path.hashCode() }
    var fontSp by remember { mutableFloatStateOf(prefs.getFloat(IndexDiscovery.KEY_FONT_SP, 17f)) }
    val chunks = remember { mutableStateMapOf<Int, List<String>>() }
    val inFlight = remember { mutableSetOf<Int>() }
    var totalLines by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(1) }
    var matches by remember { mutableIntStateOf(0) }
    var firstMatchLine by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var restored by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val terms = remember(query) {
        query.split(' ', '\n', '\t', '\u3000').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-") }
    }

    suspend fun loadPage(page: Int, initial: Boolean = false) {
        if (page < 0) return
        if (chunks.containsKey(page)) return
        val started = synchronized(inFlight) { inFlight.add(page) }
        if (!started) return
        try {
            val result = withContext(Dispatchers.IO) { IndexApi.read(path, query.ifBlank { null }, page) }
            chunks[result.page] = result.lines
            if (totalLines == 0) totalLines = result.totalLines
            totalPages = result.totalPages.coerceAtLeast(1)
            if (initial) {
                matches = result.matches
                firstMatchLine = result.firstMatchLine
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            synchronized(inFlight) { inFlight.remove(page) }
        }
    }

    LaunchedEffect(path) {
        if (!IndexApi.connected) runCatching { IndexDiscovery.discover(context) }
        loadPage(if (query.isNotBlank()) -1 else 0, initial = true)
    }

    LaunchedEffect(totalLines) {
        if (totalLines <= 0 || restored) return@LaunchedEffect
        restored = true
        val saved = prefs.getInt(posKey, -1)
        val target = when {
            saved in 0 until totalLines -> saved
            firstMatchLine >= 0 -> firstMatchLine
            else -> 0
        }
        if (target > 0) listState.scrollToItem(target)
    }

    LaunchedEffect(listState, totalLines) {
        if (totalLines <= 0) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            prefs.edit().putInt(posKey, index).apply()
            val page = index / PAGE_LINES
            for (p in (page - 1).coerceAtLeast(0)..(page + 1).coerceAtMost(totalPages - 1)) {
                loadPage(p)
            }
        }
    }

    val currentLine by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    fun applyFont(value: Float) {
        fontSp = value.coerceIn(11f, 30f)
        prefs.edit().putFloat(IndexDiscovery.KEY_FONT_SP, fontSp).apply()
    }

    Scaffold(
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
                        TextButton(onClick = { applyFont(fontSp - 1f) }) { Text("A-") }
                        TextButton(onClick = { applyFont(fontSp + 1f) }) { Text("A+") }
                        if (firstMatchLine >= 0) {
                            TextButton(onClick = { scope.launch { listState.scrollToItem(firstMatchLine.coerceIn(0, (totalLines - 1).coerceAtLeast(0))) } }) {
                                Text("首个匹配")
                            }
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
                        Spacer(Modifier.weight(1f))
                        if (matches > 0) {
                            Text("匹配 $matches 处", style = MaterialTheme.typography.labelMedium)
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
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) },
            contentPadding = padding,
        ) {
            error?.let { message ->
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("读取失败：$message", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { error = null; scope.launch { loadPage(0, initial = true) } }) {
                            Text("重试")
                        }
                    }
                }
            }
            if (totalLines == 0 && error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
            items(
                count = totalLines,
                key = { index -> index },
            ) { index ->
                val lines = chunks[index / PAGE_LINES]
                val text = lines?.getOrNull(index % PAGE_LINES)
                if (text == null) {
                    Spacer(Modifier.height((fontSp * 1.7f).dp))
                } else {
                    LineText(text, terms, fontSp)
                }
            }
        }
    }
}

@Composable
private fun LineText(raw: String, terms: List<String>, fontSp: Float) {
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSp.sp,
        lineHeight = (fontSp * 1.7f).sp,
    )
    if (raw.isEmpty()) {
        Spacer(Modifier.height((fontSp * 0.7f).dp))
        return
    }
    Text(
        text = if (terms.isEmpty()) AnnotatedString(raw) else highlight(raw, terms),
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 1.dp),
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
