package com.hippo.ehviewer.index

import android.content.Context
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 索引服务默认端口（HTTP 与 UDP 发现都用它）。 */
const val INDEX_PORT = 8765

/** 服务端 /api/read 每页行数，客户端据此把整本书映射成连续行号。 */
const val PAGE_LINES = 1500

data class IndexHit(
    val path: String,
    val name: String,
    val dir: String,
    val size: Long,
    val mtime: String,
    val folder: Boolean,
)

data class IndexSearchResult(
    val total: Int,
    val took: Long,
    val from: Int,
    val hits: List<IndexHit>,
)

data class IndexPage(
    val path: String,
    val size: Long,
    val encoding: String,
    val totalLines: Int,
    val page: Int,
    val totalPages: Int,
    val matches: Int,
    val firstMatchLine: Int,
    val firstLine: Int,
    val lines: List<String>,
    val mtime: String = "",
)

object IndexApi {
    private val client = OkHttpClient()

    @Volatile
    var base: String? = null
        private set

    val connected: Boolean
        get() = base != null

    fun setBase(value: String?) {
        base = value?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    }

    private fun full(path: String): String {
        val b = base ?: throw IOException("尚未发现索引服务器")
        return b + path
    }

    // OkHttp 的同步调用必须离开主线程，否则会抛 NetworkOnMainThreadException。
    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(full(path)).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}" + if (text.isEmpty()) "" else "：$text")
            }
            if (text.isEmpty()) throw IOException("服务器返回了空响应")
            JSONObject(text)
        }
    }

    suspend fun meta(): JSONObject = get("/api/meta")

    suspend fun search(
        terms: List<String>,
        excludes: List<String> = emptyList(),
        and: Boolean = true,
        sort: String = "rel",
        desc: Boolean = true,
        scope: String = "all",
        ext: String = "",
        from: Int = 0,
        limit: Int = 100,
    ): IndexSearchResult {
        val query = buildString {
            terms.forEach { append("&t=").append(enc(it)) }
            excludes.forEach { append("&x=").append(enc(it)) }
            append("&op=").append(if (and) "and" else "or")
            append("&sort=").append(enc(sort))
            append("&order=").append(if (desc) "desc" else "asc")
            append("&m=").append(enc(scope))
            if (ext.isNotEmpty()) append("&ext=").append(enc(ext))
            append("&from=").append(from)
            append("&limit=").append(limit)
        }
        val json = get("/api/search?1=1" + query)
        val array = json.optJSONArray("hits")
        val hits = ArrayList<IndexHit>(array?.length() ?: 0)
        for (i in 0 until (array?.length() ?: 0)) {
            val o = array!!.optJSONObject(i) ?: continue
            hits.add(
                IndexHit(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    dir = o.optString("dir"),
                    size = o.optLong("size"),
                    mtime = o.optString("mtime"),
                    folder = o.optBoolean("folder"),
                ),
            )
        }
        return IndexSearchResult(
            total = json.optInt("total"),
            took = json.optLong("took"),
            from = json.optInt("from", from),
            hits = hits,
        )
    }

    suspend fun read(path: String, query: String?, page: Int): IndexPage {
        val sb = StringBuilder("/api/read?path=").append(enc(path)).append("&page=").append(page)
        if (!query.isNullOrBlank()) sb.append("&q=").append(enc(query))
        val json = get(sb.toString())
        json.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        val lines = ArrayList<String>()
        val array = json.optJSONArray("lines")
        for (i in 0 until (array?.length() ?: 0)) lines.add(array!!.optString(i))
        return IndexPage(
            path = json.optString("path", path),
            size = json.optLong("size"),
            encoding = json.optString("encoding"),
            totalLines = json.optInt("totalLines"),
            page = json.optInt("page", page.coerceAtLeast(0)),
            totalPages = json.optInt("totalPages", 1),
            matches = json.optInt("matches"),
            firstMatchLine = json.optInt("firstMatchLine", -1),
            firstLine = json.optInt("from", 1),
            lines = lines,
            mtime = json.optString("mtime"),
        )
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * 局域网自动发现：先试上次成功过的地址，再 UDP 广播（服务器回 EHINDEX1），
 * 最后并发扫本机所在 /24 网段的 8765 端口。
 */
object IndexDiscovery {
    private const val PREFS = "index_reader"
    private const val KEY_BASE = "server_base"

    const val KEY_FONT_SP = "reader_font_sp"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(context: Context): String? = prefs(context).getString(KEY_BASE, null)

    suspend fun discover(context: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val saved = if (force) null else cached(context)
        if (saved != null && probe(saved)) {
            IndexApi.setBase(saved)
            return@withContext saved
        }
        val udpHost = udpProbe()
        if (udpHost != null) {
            val candidate = "http://$udpHost:$INDEX_PORT"
            if (probe(candidate)) {
                save(context, candidate)
                IndexApi.setBase(candidate)
                return@withContext candidate
            }
        }
        val scanned = scanLan()
        if (scanned != null) {
            save(context, scanned)
            IndexApi.setBase(scanned)
            return@withContext scanned
        }
        IndexApi.setBase(null)
        throw IOException("局域网里没有找到索引服务器（UDP/TCP $INDEX_PORT）")
    }

    private fun save(context: Context, base: String) {
        prefs(context).edit().putString(KEY_BASE, base).apply()
    }

    fun probe(base: String): Boolean = try {
        val conn = URL("$base/api/meta").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 2500
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (_: Exception) {
        false
    }

    private fun udpProbe(): String? {
        val targets = broadcastTargets()
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val message = "EHINDEX1".toByteArray()
                targets.forEach { address ->
                    runCatching { socket.send(DatagramPacket(message, message.size, address, INDEX_PORT)) }
                }
                val buffer = ByteArray(256)
                val deadline = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length).trim()
                    if (text.startsWith("EHINDEX1")) return packet.address.hostAddress
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        for (nif in networkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (address in nif.interfaceAddresses) {
                val ip = address.address
                if (ip is java.net.Inet4Address && ip.isSiteLocalAddress && address.broadcast != null) {
                    out.add(address.broadcast!!)
                }
            }
        }
        return out.toList()
    }

    private fun networkInterfaces(): List<NetworkInterface> =
        runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())

    private suspend fun scanLan(): String? {
        val local = localSubnets()
        if (local.isEmpty()) return null
        for (prefix in local) {
            val found = coroutineScope {
                (1..254).chunked(48).map { chunk ->
                    async {
                        chunk.firstNotNullOfOrNull { host ->
                            val ip = "$prefix.$host"
                            if (portOpen(ip)) "http://$ip:$INDEX_PORT" else null
                        }
                    }
                }.awaitAll().firstNotNullOfOrNull { it }
            }
            if (found != null && probe(found)) return found
        }
        return null
    }

    private fun localSubnets(): List<String> {
        val out = LinkedHashSet<String>()
        for (nif in networkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (address in nif.interfaceAddresses) {
                val ip = address.address
                if (ip is java.net.Inet4Address && ip.isSiteLocalAddress) {
                    val host = ip.hostAddress ?: continue
                    out.add(host.substringBeforeLast('.'))
                }
            }
        }
        return out.toList()
    }

    private fun portOpen(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, INDEX_PORT), 220)
            true
        }
    } catch (_: Exception) {
        false
    }
}
