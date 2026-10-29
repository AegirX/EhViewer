package com.hippo.ehviewer.index

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.IndexReaderScreenDestination
import com.hippo.ehviewer.ui.screen.SearchBarScreen
import com.hippo.ehviewer.ui.tools.DialogState
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.tarsin.navigate

private val SORTS = listOf("rel" to "相关度", "name" to "名称", "size" to "大小", "mtime" to "时间")
private val SCOPES = listOf("all" to "全文+文件名", "content" to "正文", "name" to "文件名")
private val EXTS = listOf("" to "全部类型", "txt" to "txt", "epub" to "epub")

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.IndexHomeScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    IndexSearchScaffold()
}

@Composable
context(_: DialogState, _: DestinationsNavigator, _: CoroutineScope)
fun IndexSearchScaffold() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val searchFieldState = rememberTextFieldState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<IndexHit>()) }
    var total by remember { mutableIntStateOf(0) }
    var took by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("正在局域网里查找索引服务器…") }
    var sortKey by rememberSaveable { mutableStateOf("rel") }
    var desc by rememberSaveable { mutableStateOf(true) }
    var matchAll by rememberSaveable { mutableStateOf(true) }
    var scopeKey by rememberSaveable { mutableStateOf("all") }
    var ext by rememberSaveable { mutableStateOf("") }

    fun parse(text: String): Pair<List<String>, List<String>> {
        val positive = ArrayList<String>()
        val negative = ArrayList<String>()
        text.split(' ', '\n', '\t', '\u3000').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            if (token.startsWith("-") && token.length > 1) negative.add(token.substring(1)) else positive.add(token)
        }
        return positive to negative
    }

    fun runSearch(append: Boolean = false, from: Int = 0) {
        val (positive, negative) = parse(query)
        if (positive.isEmpty() && negative.isEmpty()) return
        loading = true
        error = null
        scope.launch {
            try {
                if (!IndexApi.connected) {
                    server = IndexDiscovery.discover(context)
                }
                val result = IndexApi.search(
                    terms = positive,
                    excludes = negative,
                    and = matchAll,
                    sort = sortKey,
                    desc = desc,
                    scope = scopeKey,
                    ext = ext,
                    from = from,
                    limit = 100,
                )
                hits = if (append) hits + result.hits else result.hits
                total = result.total
                took = result.took
                status = "已连接 ${IndexApi.base}"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                IndexApi.setBase(null)
                runCatching {
                    server = IndexDiscovery.discover(context, force = true)
                    status = "已连接 $server"
                }.onFailure {
                    status = "未找到索引服务器"
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            server = IndexDiscovery.discover(context)
            status = "已连接 $server"
        }.onFailure {
            status = "未找到索引服务器：${it.message}"
        }
    }

    LaunchedEffect(sortKey, desc, matchAll, scopeKey, ext) {
        if (hits.isNotEmpty() && query.isNotBlank()) runSearch()
    }

    SearchBarScreen(
        onApplySearch = { text ->
            query = text
            runSearch()
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        title = "索引搜索",
        searchFieldHint = "搜索本地索引；空格分隔多个关键词，-词 表示排除",
        searchFieldState = searchFieldState,
        localSearch = true,
        trailingIcon = {
            IconButton(onClick = {
                status = "正在重新查找索引服务器…"
                scope.launch {
                    runCatching {
                        server = IndexDiscovery.discover(context, force = true)
                        status = "已连接 $server"
                    }.onFailure { status = "未找到索引服务器" }
                }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "重新查找服务器")
            }
        },
        filter = {
            IndexFilterBar(
                sortKey = sortKey,
                desc = desc,
                matchAll = matchAll,
                scopeKey = scopeKey,
                ext = ext,
                onSortChange = { sortKey = it },
                onToggleOrder = { desc = !desc },
                onToggleMatch = { matchAll = !matchAll },
                onScopeChange = { scopeKey = it },
                onExtChange = { ext = it },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = if (hits.isEmpty()) status else "$status · 共 ${"%,d".format(total)} 条 · ${took} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let { message ->
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("搜索失败：$message", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { runSearch() }) { Text("重试") }
                    }
                }
            }
            if (hits.isEmpty() && !loading && error == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("输入关键词搜索本机索引", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "· 多个关键词用空格分隔：默认要求全部命中，可在上方切到「任一」\n" +
                                "· 以 - 开头的词表示排除，例如：少年 -ntr\n" +
                                "· 结果按相关度/名称/大小/时间排序，点开即可阅读",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (loading && hits.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
            items(hits, key = { it.path }) { hit ->
                IndexHitRow(hit) {
                    navigate(IndexReaderScreenDestination(hit.path, hit.name, query))
                }
            }
            if (!loading && hits.isNotEmpty() && hits.size < total) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { runSearch(append = true, from = hits.size) }) {
                            Text("加载更多（已显示 ${hits.size} / ${"%,d".format(total)}）")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexHitRow(hit: IndexHit, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(hit.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Text(hit.dir, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Text(
                "${formatSize(hit.size)}\n${hit.mtime}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun IndexFilterBar(
    sortKey: String,
    desc: Boolean,
    matchAll: Boolean,
    scopeKey: String,
    ext: String,
    onSortChange: (String) -> Unit,
    onToggleOrder: () -> Unit,
    onToggleMatch: () -> Unit,
    onScopeChange: (String) -> Unit,
    onExtChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = matchAll, onClick = onToggleMatch, label = { Text(if (matchAll) "全部命中" else "任一命中") })
        SORTS.forEach { (key, label) ->
            FilterChip(selected = sortKey == key, onClick = { onSortChange(key) }, label = { Text(label) })
        }
        TextButton(onClick = onToggleOrder) { Text(if (desc) "↓" else "↑") }
        SCOPES.forEach { (key, label) ->
            FilterChip(selected = scopeKey == key, onClick = { onScopeChange(key) }, label = { Text(label) })
        }
        EXTS.forEach { (key, label) ->
            FilterChip(selected = ext == key, onClick = { onExtChange(key) }, label = { Text(label) })
        }
        Spacer(Modifier.width(4.dp))
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
