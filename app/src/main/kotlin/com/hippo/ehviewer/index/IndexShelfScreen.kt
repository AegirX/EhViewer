package com.hippo.ehviewer.index

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.ui.LocalNavDrawerState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.IndexHomeScreenDestination
import com.hippo.ehviewer.ui.destinations.IndexReaderScreenDestination
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShelfKind { History, Favorite }

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.IndexHistoryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    IndexShelfContent(kind = ShelfKind.History, navigator = navigator)
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.IndexFavoritesScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    IndexShelfContent(kind = ShelfKind.Favorite, navigator = navigator)
}

@Composable
context(_: DialogState)
fun IndexShelfContent(kind: ShelfKind, navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = LocalNavDrawerState.current
    var items by remember { mutableStateOf(emptyList<ShelfEntry>()) }
    var version by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyPath by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(version) {
        items = withContext(Dispatchers.IO) {
            if (kind == ShelfKind.History) IndexStore.history(context) else IndexStore.favorites(context)
        }
        loading = false
    }

    val title = if (kind == ShelfKind.History) "历史" else "收藏"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "菜单")
                    }
                },
                actions = {
                    IconButton(onClick = { navigator.navigate(IndexHomeScreenDestination) }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    if (kind == ShelfKind.History && items.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    awaitConfirmationOrCancel(text = {
                                        Text("清空全部历史记录，并删除它们在本机的正文缓存？已收藏的书会保留。")
                                    })
                                    withContext(Dispatchers.IO) { IndexStore.clearHistory(context) }
                                    version++
                                }
                            },
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空历史")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (kind == ShelfKind.History) "还没有阅读记录" else "还没有收藏的书")
                    Text(
                        if (kind == ShelfKind.History) "打开任意搜索结果后会自动记录" else "在搜索结果里点右侧的 ♥ 即可收藏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyColumn(contentPadding = padding) {
                items(items, key = { it.path }) { entry ->
                    ShelfRow(
                        entry = entry,
                        kind = kind,
                        busy = busyPath == entry.path,
                        progress = progress,
                        onOpen = {
                            navigator.navigate(
                                IndexReaderScreenDestination(
                                    path = entry.path,
                                    name = entry.name,
                                    query = "",
                                    dir = entry.dir,
                                    size = entry.size,
                                    mtime = entry.mtime,
                                ),
                            )
                        },
                        onCache = {
                            scope.launch {
                                busyPath = entry.path
                                progress = 0 to 0
                                runCatching {
                                    IndexStore.cacheAll(context, entry.path, entry.name, entry.dir) { done, total ->
                                        progress = done to total
                                    }
                                }
                                busyPath = null
                                version++
                            }
                        },
                        onRemove = {
                            scope.launch {
                                if (kind == ShelfKind.History) {
                                    awaitConfirmationOrCancel(text = {
                                        Text("删除这条阅读记录，并删除它的本地缓存？")
                                    })
                                    withContext(Dispatchers.IO) { IndexStore.removeHistory(context, entry.path, deleteCache = true) }
                                } else {
                                    awaitConfirmationOrCancel(text = {
                                        Text("取消收藏，并删除已下载到本机的正文缓存？")
                                    })
                                    withContext(Dispatchers.IO) { IndexStore.removeFavorite(context, entry.path, deleteCache = true) }
                                }
                                version++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    entry: ShelfEntry,
    kind: ShelfKind,
    busy: Boolean,
    progress: Pair<Int, Int>,
    onOpen: () -> Unit,
    onCache: () -> Unit,
    onRemove: () -> Unit,
) {
    val cached = entry.cachedPages > 0
    val complete = entry.totalPages > 0 && entry.cachedPages >= entry.totalPages
    ListItem(
        headlineContent = {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.dir, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    buildString {
                        if (entry.totalLines > 0) {
                            append("第 ${entry.lastLine + 1} / ${entry.totalLines} 行")
                        }
                        if (entry.cachedPages > 0) {
                            if (isNotEmpty()) append("  ·  ")
                            append("已缓存 ${entry.cachedPages}${if (entry.totalPages > 0) "/${entry.totalPages}" else ""} 页")
                        } else {
                            if (isNotEmpty()) append("  ·  ")
                            append("未缓存")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy && progress.second > 0) {
                    LinearProgressIndicator(
                        progress = { progress.first.toFloat() / progress.second },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text("正在缓存 ${progress.first}/${progress.second} 页", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kind == ShelfKind.Favorite) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        IconButton(onClick = onCache, enabled = !complete) {
                            Icon(
                                if (complete) Icons.Default.DownloadDone else Icons.Default.Download,
                                contentDescription = if (complete) "已缓存" else "下载到本地",
                            )
                        }
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = if (kind == ShelfKind.History) "删除记录" else "取消收藏",
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}
