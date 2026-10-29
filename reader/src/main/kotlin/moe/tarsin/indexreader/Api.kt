package moe.tarsin.indexreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Meta(val docs: Int, val folders: Int, val roots: List<String>)

data class Hit(
    val path: String,
    val name: String,
    val dir: String,
    val size: Long,
    val mtime: String,
    val folder: Boolean,
)

data class SearchResult(val total: Int, val took: Int, val hits: List<Hit>)

data class ReadPage(
    val path: String,
    val encoding: String,
    val totalLines: Int,
    val page: Int,
    val totalPages: Int,
    val matches: Int,
    val firstMatchLine: Int,
    val lines: List<String>,
)

object Api {
    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val hostPart = s.substringAfter("://")
        if (!hostPart.contains(':')) s = s.trimEnd('/') + ":8765"
        return s.trimEnd('/')
    }
    private fun exec(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Connection", "close")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${body.take(120)}")
            val json = JSONObject(body)
            if (json.has("error")) throw IOException(json.optString("error"))
            return json
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun get(base: String, path: String, params: List<Pair<String, String>>): JSONObject =
        withContext(Dispatchers.IO) {
            val root = normalizeServer(base)
            if (root.isEmpty()) throw IOException("服务器地址为空")
            val query = params.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
            val url = root + path + if (query.isEmpty()) "" else "?$query"
            var lastError: IOException? = null
            for (attempt in 0 until 2) {
                try {
                    return@withContext exec(url)
                } catch (e: IOException) {
                    lastError = e
                    if (attempt == 0) Thread.sleep(300)
                }
            }
            throw IOException("连不上 $url：${lastError?.message ?: "未知错误"}")
        }

    suspend fun meta(base: String): Meta {
        val json = get(base, "/api/meta", emptyList())
        val roots = mutableListOf<String>()
        val array = json.optJSONArray("roots")
        if (array != null) for (i in 0 until array.length()) roots.add(array.optString(i))
        return Meta(json.optInt("docs"), json.optInt("folders"), roots)
    }

    suspend fun search(
        base: String,
        terms: List<String>,
        excludes: List<String>,
        or: Boolean,
        scope: String,
        sort: String,
        order: String,
        ext: String,
        lookin: String,
    ): SearchResult {
        val params = mutableListOf<Pair<String, String>>()
        terms.forEach { params.add("t" to it) }
        excludes.forEach { params.add("x" to it) }
        params.add("op" to if (or) "or" else "and")
        params.add("m" to scope)
        params.add("sort" to sort)
        params.add("order" to order)
        if (ext.isNotBlank()) params.add("ext" to ext.trim())
        if (lookin.isNotBlank()) params.add("lookin" to lookin.trim())
        params.add("limit" to "200")

        val json = get(base, "/api/search", params)
        val hits = mutableListOf<Hit>()
        val array = json.optJSONArray("hits")
        if (array != null) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                hits.add(
                    Hit(
                        path = o.optString("path"),
                        name = o.optString("name"),
                        dir = o.optString("dir"),
                        size = o.optLong("size"),
                        mtime = o.optString("mtime"),
                        folder = o.optBoolean("folder"),
                    ),
                )
            }
        }
        return SearchResult(json.optInt("total"), json.optInt("took"), hits)
    }

    suspend fun read(base: String, path: String, page: Int, query: String): ReadPage {
        val params = mutableListOf("path" to path, "page" to page.toString())
        if (query.isNotBlank()) params.add("q" to query)
        val json = get(base, "/api/read", params)
        val lines = mutableListOf<String>()
        val array = json.optJSONArray("lines")
        if (array != null) for (i in 0 until array.length()) lines.add(array.optString(i))
        return ReadPage(
            path = json.optString("path"),
            encoding = json.optString("encoding"),
            totalLines = json.optInt("totalLines"),
            page = json.optInt("page"),
            totalPages = json.optInt("totalPages"),
            matches = json.optInt("matches"),
            firstMatchLine = json.optInt("firstMatchLine", -1),
            lines = lines,
        )
    }
}
