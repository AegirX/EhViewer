package moe.tarsin.indexreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.tarsin.indexreader.Api
import moe.tarsin.indexreader.Hit
import moe.tarsin.indexreader.Prefs

@Composable
fun ReaderScreen(
    server: String,
    hit: Hit,
    query: String,
    prefs: Prefs,
    font: ReaderFont,
    fontSize: Float,
    theme: AppTheme,
    onBack: () -> Unit,
    onFont: (Float, ReaderFont) -> Unit,
    onTheme: (AppTheme) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val terms = remember(query) { query.split(' ').filter { it.isNotBlank() } }

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var totalLines by remember { mutableStateOf(0) }
    var chunk by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    BackHandler { onBack() }

    LaunchedEffect(hit.path, query) {
        loading = true
        error = ""
        lines = emptyList()
        totalLines = 0
        chunk = 0
        try {
            val first = Api.read(server, hit.path, 0, query)
            var acc = first.lines
            totalLines = first.totalLines
            var index = 1
            val target = if (first.firstMatchLine >= 0) first.firstMatchLine else prefs.position(hit.path)
            while (acc.size <= target && acc.size < first.totalLines) {
                val more = Api.read(server, hit.path, index, query)
                index += 1
                acc = acc + more.lines
            }
            lines = acc
            chunk = index
            if (target in acc.indices) listState.scrollToItem(target)
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(listState, hit.path) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { prefs.setPosition(hit.path, it) }
    }

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lines.isNotEmpty() && lines.size < totalLines && last >= lines.size - 40
        }
    }

    LaunchedEffect(nearEnd) {
        if (!nearEnd || loadingMore) return@LaunchedEffect
        loadingMore = true
        try {
            val next = Api.read(server, hit.path, chunk, query)
            chunk += 1
            lines = lines + next.lines
        } catch (_: Exception) {
        } finally {
            loadingMore = false
        }
    }

    val progress by remember {
        derivedStateOf {
            if (totalLines <= 0) 0f else (listState.firstVisibleItemIndex + 1f) / totalLines
        }
    }

    val textStyle = androidx.compose.ui.text.TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.9f).sp,
        fontFamily = font.family(),
        color = MaterialTheme.colorScheme.onBackground,
    )
    val markStyle = SpanStyle(
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Medium,
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (error.isNotEmpty()) {
            Text(
                error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        when {
                            offset.x < w * 0.3f -> scope.launch { listState.scrollBy(-h * 0.85f) }
                            offset.x > w * 0.7f -> scope.launch { listState.scrollBy(h * 0.85f) }
                            else -> panel = !panel
                        }
                    }
                },
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    text = buildHighlighted(line, terms, markStyle),
                    style = textStyle,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }

        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.outline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        if (panel) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "←",
                    Modifier.clickable { onBack() }.padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        hit.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${totalLines} 行 · ${formatSize(hit.size)} · ${hit.mtime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Control("A-") { onFont((fontSize - 1f).coerceIn(12f, 30f), font) }
                    Control("A+") { onFont((fontSize + 1f).coerceIn(12f, 30f), font) }
                    Spacer(Modifier.width(6.dp))
                    Control(fontLabel(font)) {
                        val values = ReaderFont.values()
                        onFont(fontSize, values[(font.ordinal + 1) % values.size])
                    }
                    Control(themeLabel(theme)) {
                        val values = AppTheme.values()
                        onTheme(values[(theme.ordinal + 1) % values.size])
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Control(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private fun fontLabel(font: ReaderFont): String = when (font) {
    ReaderFont.Serif -> "宋体"
    ReaderFont.Sans -> "黑体"
    ReaderFont.Mono -> "等宽"
    ReaderFont.System -> "系统"
}

private fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.Dark -> "深色"
    AppTheme.Light -> "浅色"
    AppTheme.Sepia -> "纸色"
}

fun buildHighlighted(line: String, terms: List<String>, span: SpanStyle): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(line)
    val lower = line.lowercase()
    val ranges = mutableListOf<IntRange>()
    for (term in terms) {
        val needle = term.lowercase()
        if (needle.isBlank()) continue
        var index = lower.indexOf(needle)
        while (index >= 0) {
            ranges.add(index until index + needle.length)
            index = lower.indexOf(needle, index + maxOf(1, needle.length))
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(line)
    ranges.sortBy { it.first }
    return buildAnnotatedString {
        var cursor = 0
        for (range in ranges) {
            if (range.first < cursor) continue
            append(line.substring(cursor, range.first))
            withStyle(span) { append(line.substring(range.first, range.last + 1)) }
            cursor = range.last + 1
        }
        append(line.substring(cursor))
    }
}
