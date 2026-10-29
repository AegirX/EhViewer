package moe.tarsin.indexreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.tarsin.indexreader.Api
import moe.tarsin.indexreader.Hit
import moe.tarsin.indexreader.Prefs

@Composable
fun IndexReaderApp(prefs: Prefs) {
    var themeName by remember { mutableStateOf(prefs.theme) }
    var fontName by remember { mutableStateOf(prefs.font) }
    var fontSize by remember { mutableStateOf(prefs.fontSize) }
    var server by remember { mutableStateOf(prefs.server) }
    var setup by remember { mutableStateOf(server.isBlank()) }
    var opened by remember { mutableStateOf<Hit?>(null) }
    var query by remember { mutableStateOf("") }

    val theme: AppTheme = themeName.toTheme()
    val font: ReaderFont = fontName.toFont()

    MaterialTheme(colorScheme = theme.colorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                setup -> SetupScreen(
                    initial = server,
                    onConnected = { address ->
                        server = address
                        prefs.server = address
                        setup = false
                    },
                )

                opened != null -> ReaderScreen(
                    server = server,
                    hit = opened!!,
                    query = query,
                    prefs = prefs,
                    font = font,
                    fontSize = fontSize,
                    onBack = { opened = null },
                    onFont = { size: Float, f: ReaderFont ->
                        fontSize = size
                        fontName = f.name.lowercase()
                        prefs.fontSize = size
                        prefs.font = fontName
                    },
                    onTheme = { t: AppTheme ->
                        themeName = t.name.lowercase()
                        prefs.theme = themeName
                    },
                    theme = theme,
                )

                else -> SearchScreen(
                    server = server,
                    onOpen = { hit, q ->
                        query = q
                        opened = hit
                    },
                    onServer = { setup = true },
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(initial: String, onConnected: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("索引阅读器", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "连接到运行索引服务的电脑",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.10:8765") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    busy = true
                    status = "连接中…"
                    scope.launch {
                        try {
                            val meta = Api.meta(text)
                            status = "已连接：${meta.docs} 条索引，${meta.folders} 个文件夹"
                            onConnected(text.trim())
                        } catch (e: Exception) {
                            status = "连接失败：" + (e.message ?: "未知错误")
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("连接") }
            Spacer(Modifier.width(12.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
