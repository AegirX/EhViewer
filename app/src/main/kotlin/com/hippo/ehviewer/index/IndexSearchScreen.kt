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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.IndexReaderScreenDestination
import com.hippo.ehviewer.ui.screen.SearchBarScreen
import com.hippo.ehviewer.ui.tools.DialogState
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tarsin.navigate

private val SORTS = listOf("rel" to "相关度", "name" to "名称", "size" to "大小", "mtime" to "时间")
private val SCOPES = listOf("all" to "全文+文件名", "content" to "正文", "name" to "文件名")
private val EXTS = listOf("" to "全部类型", "txt" to "txt", "epub" to "epub")

/** 搜索结果状态放在 ViewModel 里，从阅读页返回时列表不会丢。 */
class IndexSearchViewModel : ViewModel() {
    var query by mutableStateOf("")
    var hits by mutableStateOf(emptyList<IndexHit>())
    var total by mutableStateOf(0)
    var took by mutableLongStateOf(0L)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf("正在局域网里查找索引服务器…")
    var sortKey by mutableStateOf("rel")
    var desc by mutableStateOf(true)
    var matchAll by mutableStateOf(true)
    var scopeKey by mutableStateOf("all")
    var ext by mutableStateOf("")
    var discovered by mutableStateOf(false)
}

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
    val vm: IndexSearchViewModel = viewModel()
    val listState = rememberLazyListState()
    val searchFieldState = rememberTextFieldState()
    var expanded by remember { mutableStateOf(false) }
    val favorites = remember { mutableStateMapOf<String, Boolean>() }
    var filterReady by remember { mutableStateOf(false) }

    fun parse(text: String): Pair<List<String>, List<String>> {
        val positive = ArrayList<String>()
        val negative = ArrayList<String>()
        text.split(' ', '\n', '\t', '\u3000').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            if (token.startsWith("-") && token.length > 1) negative.add(token.substring(1)) else positive.add(token)
        }
        return positive to negative
    }

    fun runSearch(append: Boolean = false, from: Int = 0) {
        val (positive, negative) = parse(vm.query)
        if (positive.isEmpty() && negative.isEmpty()) return
        vm.loading = true
        vm.error = null
        scope.launch {
            try {
                if (!IndexApi.connected) {
                    vm.status = "已连接 ${IndexDiscovery.discover(context)}"
                    vm.discovered = true
                }
                val result = IndexApi.search(
                    terms = positive,
                    excludes = negative,
                    and = vm.matchAll,
                    sort = vm.sortKey,
                    desc = vm.desc,
                    scope = vm.scopeKey,
                    ext = vm.ext,
                    from = from,
                    limit = 100,
                )
                vm.hits = if (append) vm.hits + result.hits else result.hits
                vm.total = result.total
                vm.took = result.took
                vm.status = "已连接 ${IndexApi.base}"
            } catch (e: Exception) {
                vm.error = e.message ?: e.toString()
                IndexApi.setBase(null)
                runCatching {
                    vm.status = "已连接 ${IndexDiscovery.discover(context, force = true)}"
                    vm.discovered = true
                }.onFailure {
                    vm.status = "未找到索引服务器"
                    vm.discovered = false
                }
            } finally {
                vm.loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!vm.discovered) {
            runCatching {
                vm.status = "已连接 ${IndexDiscovery.discover(context)}"
                vm.discovered = true
            }.onFailure {
                vm.status = "未找到索引服务器：${it.message}"
            }
        }
    }

    LaunchedEffect(vm.sortKey, vm.desc, vm.matchAll, vm.scopeKey, vm.ext) {
        // 首次进入（包含从阅读页返回）不重新搜索，只有真的改了筛选条件才刷新
        if (!filterReady) {
            filterReady = true
            return@LaunchedEffect
        }
        if (vm.hits.isNotEmpty() && vm.query.isNotBlank()) runSearch()
    }

    LaunchedEffect(vm.hits) {
        if (vm.hits.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            vm.hits.forEach { hit ->
                favorites[hit.path] = IndexStore.entry(context, hit.path)?.favorite == true
            }
        }
    }

    fun toggleFavorite(hit: IndexHit) {
        val next = favorites[hit.path] != true
        favorites[hit.path] = next
        scope.launch(Dispatchers.IO) {
            IndexStore.touch(context, hit.path, hit.name, hit.dir, hit.size, hit.mtime, 0, 0)
            IndexStore.setFavorite(context, hit.path, next)
            // 收藏即把正文拉到本地，断网也能读
            if (next) runCatching { IndexStore.cacheAll(context, hit.path, hit.name, hit.dir) }
        }
    }

    SearchBarScreen(
        onApplySearch = { text ->
            vm.query = text
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
                vm.status = "正在重新查找索引服务器…"
                scope.launch {
                    runCatching {
                        vm.status = "已连接 ${IndexDiscovery.discover(context, force = true)}"
                        vm.discovered = true
                    }.onFailure {
                        vm.status = "未找到索引服务器"
                        vm.discovered = false
                    }
                }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "重新查找服务器")
            }
        },
        filter = {
            IndexFilterBar(
                sortKey = vm.sortKey,
                desc = vm.desc,
                matchAll = vm.matchAll,
                scopeKey = vm.scopeKey,
                ext = vm.ext,
                onSortChange = { vm.sortKey = it },
                onToggleOrder = { vm.desc = !vm.desc },
                onToggleMatch = { vm.matchAll = !vm.matchAll },
                onScopeChange = { vm.scopeKey = it },
                onExtChange = { vm.ext = it },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = if (vm.hits.isEmpty()) vm.status else "${vm.status} · 共 ${"%,d".format(vm.total)} 条 · ${vm.took} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            vm.error?.let { message ->
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("搜索失败：$message", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { runSearch() }) { Text("重试") }
                    }
                }
            }
            if (vm.hits.isEmpty() && !vm.loading && vm.error == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("输入关键词搜索本机索引", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "· 多个关键词用空格分隔：默认要求全部命中，可在上方切到「任一」\n" +
                                "· 以 - 开头的词表示排除，例如：少年 -ntr\n" +
                                "· 点右侧 ♥ 收藏，点条目直接阅读",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (vm.loading && vm.hits.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
            items(vm.hits, key = { it.path }) { hit ->
                IndexHitRow(
                    hit = hit,
                    favorite = favorites[hit.path] == true,
                    onOpen = {
                        navigate(
                            IndexReaderScreenDestination(
                                path = hit.path,
                                name = hit.name,
                                query = vm.query,
                                dir = hit.dir,
                                size = hit.size,
                                mtime = hit.mtime,
                            ),
                        )
                    },
                    onFavorite = { toggleFavorite(hit) },
                )
            }
            if (!vm.loading && vm.hits.isNotEmpty() && vm.hits.size < vm.total) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { runSearch(append = true, from = vm.hits.size) }) {
                            Text("加载更多（已显示 ${vm.hits.size} / ${"%,d".format(vm.total)}）")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexHitRow(
    hit: IndexHit,
    favorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(hit.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Text(hit.dir, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatSize(hit.size)}\n${hit.mtime}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    textAlign = TextAlign.End,
                )
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (favorite) "取消收藏" else "收藏",
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
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
