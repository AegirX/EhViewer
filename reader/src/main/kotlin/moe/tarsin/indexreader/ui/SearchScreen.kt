package moe.tarsin.indexreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.tarsin.indexreader.Api
import moe.tarsin.indexreader.Hit
import moe.tarsin.indexreader.parseQuery

@Composable
fun SearchScreen(server: String, onOpen: (Hit, String) -> Unit, onServer: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var or by remember { mutableStateOf(false) }
    var searchScope by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("rel") }
    var desc by remember { mutableStateOf(true) }
    var ext by remember { mutableStateOf("") }
    var lookin by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var took by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun runSearch() {
        val spec = parseQuery(input)
        if (spec.terms.isEmpty() && spec.excludes.isEmpty() && ext.isBlank() && lookin.isBlank()) return
        busy = true
        error = ""
        scope.launch {
            try {
                val result = Api.search(
                    base = server,
                    terms = spec.terms,
                    excludes = spec.excludes,
                    or = or,
                    scope = searchScope,
                    sort = sort,
                    order = if (desc) "desc" else "asc",
                    ext = ext,
                    lookin = lookin,
                )
                results = result.hits
                total = result.total
                took = result.took
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            placeholder = { Text("关键词，空格分词，-词 排除") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runSearch() }, enabled = !busy) {
                Text(if (busy) "搜索中" else "搜索")
            }
            Toggle("与", !or) { or = false }
            Toggle("或", or) { or = true }
            Spacer(Modifier.weight(1f))
            Text(server, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallToggle("全部", searchScope == "all") { searchScope = "all" }
            SmallToggle("正文", searchScope == "content") { searchScope = "content" }
            SmallToggle("文件名", searchScope == "name") { searchScope = "name" }
            Spacer(Modifier.weight(1f))
            SortMenu(sort) { sort = it }
            Button(onClick = { desc = !desc }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(if (desc) "↓" else "↑")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    error.isNotEmpty() -> error
                    total > 0 -> "$total 条 · $took ms"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (showFilters) "收起" else "筛选",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showFilters = !showFilters }.padding(8.dp),
            )
        }
        if (showFilters) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ext,
                    onValueChange = { ext = it },
                    singleLine = true,
                    label = { Text("扩展名") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lookin,
                    onValueChange = { lookin = it },
                    singleLine = true,
                    label = { Text("目录包含") },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
        LazyColumn(Modifier.fillMaxSize()) {
            items(results) { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val spec = parseQuery(input)
                            onOpen(hit, spec.terms.joinToString(" "))
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text(
                        hit.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        hit.dir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatSize(hit.size)} · ${hit.mtime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (active) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) { Text(label) }
}

@Composable
private fun SmallToggle(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

private val sortLabels = listOf(
    "rel" to "相关度",
    "name" to "名称",
    "size" to "大小",
    "mtime" to "时间",
)

@Composable
private fun SortMenu(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            sortLabels.firstOrNull { it.first == current }?.second ?: "相关度",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sortLabels.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPick(value)
                        open = false
                    },
                )
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
