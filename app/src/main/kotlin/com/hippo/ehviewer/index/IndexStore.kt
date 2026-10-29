package com.hippo.ehviewer.index

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 阅读记录 / 收藏 共用一个条目：被收藏后同时保留历史位置。 */
data class ShelfEntry(
    val path: String,
    val name: String,
    val dir: String,
    val size: Long,
    val mtime: String,
    val lastLine: Int,
    val totalLines: Int,
    val updatedAt: Long,
    val favorite: Boolean,
    val cachedPages: Int,
    val totalPages: Int,
) {
    val progress: Float
        get() = if (totalLines <= 0) 0f else (lastLine.toFloat() / totalLines).coerceIn(0f, 1f)
}

/** 一页正文，可能来自网络，也可能来自本地缓存。 */
data class CachedPage(
    val page: IndexPage,
    val fromCache: Boolean,
)

/**
 * 本地书架：阅读记录（历史）、收藏，以及两者的正文缓存。
 *
 * 缓存放 `filesDir/index_cache/<sha1(path)>/p<page>.txt`，删历史/取消收藏时按需清理。
 */
object IndexStore {
    private const val FILE_NAME = "index_shelf.json"
    private const val CACHE_DIR = "index_cache"

    private val lock = Any()
    private var loaded = false
    private val entries = LinkedHashMap<String, ShelfEntry>()

    // ------------------------------------------------------------ 记录

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = file(context)
            if (f.isFile) {
                runCatching {
                    val array = JSONObject(f.readText()).optJSONArray("entries") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val o = array.optJSONObject(i) ?: continue
                        val path = o.optString("path")
                        if (path.isEmpty()) continue
                        entries[path] = ShelfEntry(
                            path = path,
                            name = o.optString("name"),
                            dir = o.optString("dir"),
                            size = o.optLong("size"),
                            mtime = o.optString("mtime"),
                            lastLine = o.optInt("lastLine"),
                            totalLines = o.optInt("totalLines"),
                            updatedAt = o.optLong("updatedAt"),
                            favorite = o.optBoolean("favorite"),
                            cachedPages = o.optInt("cachedPages"),
                            totalPages = o.optInt("totalPages"),
                        )
                    }
                }
            }
            loaded = true
        }
    }

    private fun save(context: Context) {
        val array = JSONArray()
        for (e in entries.values) {
            array.put(
                JSONObject()
                    .put("path", e.path)
                    .put("name", e.name)
                    .put("dir", e.dir)
                    .put("size", e.size)
                    .put("mtime", e.mtime)
                    .put("lastLine", e.lastLine)
                    .put("totalLines", e.totalLines)
                    .put("updatedAt", e.updatedAt)
                    .put("favorite", e.favorite)
                    .put("cachedPages", e.cachedPages)
                    .put("totalPages", e.totalPages),
            )
        }
        runCatching {
            val f = file(context)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(JSONObject().put("entries", array).toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    fun history(context: Context): List<ShelfEntry> {
        ensureLoaded(context)
        synchronized(lock) {
            return entries.values.filter { it.updatedAt > 0 && it.totalLines > 0 }
                .sortedByDescending { it.updatedAt }
        }
    }

    fun favorites(context: Context): List<ShelfEntry> {
        ensureLoaded(context)
        synchronized(lock) {
            return entries.values.filter { it.favorite }.sortedByDescending { it.updatedAt }
        }
    }

    fun entry(context: Context, path: String): ShelfEntry? {
        ensureLoaded(context)
        synchronized(lock) { return entries[path] }
    }

    /** 记录一次打开/阅读：写入阅读位置，保留是否已收藏。 */
    fun touch(
        context: Context,
        path: String,
        name: String,
        dir: String,
        size: Long,
        mtime: String,
        lastLine: Int,
        totalLines: Int,
    ): ShelfEntry {
        ensureLoaded(context)
        synchronized(lock) {
            val old = entries[path]
            val now = System.currentTimeMillis()
            val merged = ShelfEntry(
                path = path,
                name = name.ifEmpty { old?.name ?: path.substringAfterLast('/').substringAfterLast('\\') },
                dir = dir.ifEmpty { old?.dir.orEmpty() },
                size = if (size > 0) size else old?.size ?: 0L,
                mtime = mtime.ifEmpty { old?.mtime.orEmpty() },
                lastLine = lastLine.coerceAtLeast(0),
                totalLines = if (totalLines > 0) totalLines else old?.totalLines ?: 0,
                updatedAt = now,
                favorite = old?.favorite ?: false,
                cachedPages = old?.cachedPages ?: 0,
                totalPages = old?.totalPages ?: 0,
            )
            entries[path] = merged
            save(context)
            return merged
        }
    }

    fun setFavorite(context: Context, path: String, value: Boolean): ShelfEntry? {
        ensureLoaded(context)
        synchronized(lock) {
            val old = entries[path] ?: return null
            val updated = old.copy(favorite = value, updatedAt = if (value) System.currentTimeMillis() else old.updatedAt)
            entries[path] = updated
            save(context)
            return updated
        }
    }

    /** 删除一条历史；[deleteCache] 为 true 时同时删除它的本地正文缓存。 */
    fun removeHistory(context: Context, path: String, deleteCache: Boolean = true) {
        ensureLoaded(context)
        synchronized(lock) {
            val old = entries[path]
            if (old != null && old.favorite) {
                // 已收藏的文件只清掉阅读位置，保留条目与缓存
                entries[path] = old.copy(lastLine = 0, updatedAt = 0)
            } else {
                entries.remove(path)
            }
            if (deleteCache) deleteCache(context, path)
            save(context)
        }
    }

    /** 清空历史：收藏的条目保留（位置归零），其余连同缓存一起删除。 */
    fun clearHistory(context: Context) {
        ensureLoaded(context)
        synchronized(lock) {
            val removed = LinkedHashMap<String, ShelfEntry>()
            for ((path, entry) in entries) {
                if (entry.favorite) {
                    entries[path] = entry.copy(lastLine = 0, updatedAt = 0)
                } else {
                    removed[path] = entry
                }
            }
            for (path in removed.keys) {
                entries.remove(path)
                deleteCache(context, path)
            }
            save(context)
        }
    }

    fun removeFavorite(context: Context, path: String, deleteCache: Boolean) {
        ensureLoaded(context)
        synchronized(lock) {
            val old = entries[path] ?: return
            if (old.updatedAt > 0) {
                entries[path] = old.copy(favorite = false)
            } else {
                entries.remove(path)
            }
            if (deleteCache) deleteCache(context, path)
            save(context)
        }
    }

    // ------------------------------------------------------------ 缓存

    private fun cacheKey(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun cacheDir(context: Context, path: String) = File(File(context.filesDir, CACHE_DIR), cacheKey(path))

    private fun pageFile(context: Context, path: String, page: Int) = File(cacheDir(context, path), "p$page.txt")

    fun cachedPages(context: Context, path: String): Int {
        val dir = cacheDir(context, path)
        if (!dir.isDirectory) return 0
        return dir.listFiles()?.count { it.name.startsWith("p") && it.name.endsWith(".txt") } ?: 0
    }

    fun deleteCache(context: Context, path: String) {
        cacheDir(context, path).deleteRecursively()
        synchronized(lock) {
            val old = entries[path] ?: return
            entries[path] = old.copy(cachedPages = 0)
            save(context)
        }
    }

    private fun writePage(context: Context, path: String, page: Int, lines: List<String>) {
        runCatching {
            val dir = cacheDir(context, path)
            dir.mkdirs()
            pageFile(context, path, page).writeText(lines.joinToString("\n"))
        }
    }

    private fun readCached(context: Context, path: String, page: Int): List<String>? {
        val f = pageFile(context, path, page)
        if (!f.isFile) return null
        return runCatching { f.readText().split("\n") }.getOrNull()
    }

    private fun updateCachedCount(context: Context, path: String, totalPages: Int) {
        synchronized(lock) {
            val old = entries[path] ?: return
            val pages = cachedPages(context, path)
            entries[path] = old.copy(cachedPages = pages, totalPages = totalPages.coerceAtLeast(old.totalPages))
            save(context)
        }
    }

    // ------------------------------------------------------------ 读取

    /**
     * 读一页：优先走网络并顺手写缓存；网络不可用时回退到本地缓存。
     * page = -1 表示“跳到第一个匹配页”（仅在线可用）。
     */
    suspend fun readPage(
        context: Context,
        path: String,
        name: String,
        dir: String,
        query: String,
        page: Int,
    ): CachedPage = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        val requested = if (page < 0 && !IndexApi.connected) 0 else page
        try {
            val result = IndexApi.read(path, query.ifBlank { null }, requested)
            writePage(context, path, result.page, result.lines)
            val known = synchronized(lock) { entries[path] }
            touch(
                context = context,
                path = path,
                name = name,
                dir = dir,
                size = result.size,
                mtime = result.mtime,
                lastLine = known?.lastLine ?: 0,
                totalLines = result.totalLines,
            )
            updateCachedCount(context, path, result.totalPages)
            CachedPage(result, false)
        } catch (e: Exception) {
            val fallbackPage = requested.coerceAtLeast(0)
            val cached = readCached(context, path, fallbackPage) ?: readCached(context, path, 0)
            if (cached == null) throw e
            val known = synchronized(lock) { entries[path] }
            val totalLines = known?.totalLines ?: 0
            val totalPages = (known?.totalPages ?: 1).coerceAtLeast(1)
            CachedPage(
                page = IndexPage(
                    path = path,
                    size = known?.size ?: 0L,
                    encoding = "本地缓存",
                    totalLines = totalLines,
                    page = fallbackPage,
                    totalPages = totalPages,
                    matches = 0,
                    firstMatchLine = -1,
                    firstLine = fallbackPage * PAGE_LINES + 1,
                    lines = cached,
                ),
                fromCache = true,
            )
        }
    }

    /** 把整本书拉进本地缓存（收藏离线阅读用）。 */
    suspend fun cacheAll(
        context: Context,
        path: String,
        name: String,
        dir: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        var page = 0
        var totalPages = 1
        var totalLines = 0
        var size = 0L
        var mtime = ""
        while (page < totalPages) {
            val result = IndexApi.read(path, null, page)
            writePage(context, path, result.page, result.lines)
            totalPages = result.totalPages.coerceAtLeast(1)
            totalLines = result.totalLines
            size = result.size
            mtime = result.mtime
            page = result.page + 1
            onProgress(page.coerceAtMost(totalPages), totalPages)
        }
        val known = synchronized(lock) { entries[path] }
        touch(
            context = context,
            path = path,
            name = name,
            dir = dir,
            size = size,
            mtime = mtime,
            lastLine = known?.lastLine ?: 0,
            totalLines = totalLines,
        )
        synchronized(lock) {
            entries[path] = entries[path]!!.copy(cachedPages = totalPages, totalPages = totalPages)
            save(context)
        }
        totalPages
    }
}

/** 阅读界面的个性化设置（字号、行距、字体、页边距、背景）。 */
object IndexReaderPrefs {
    private const val KEY_FONT_SP = "reader_font_sp"
    private const val KEY_LINE = "reader_line_height"
    private const val KEY_FAMILY = "reader_font_family"
    private const val KEY_MARGIN = "reader_margin_dp"
    private const val KEY_BG = "reader_background"

    const val FAMILY_SYSTEM = 0
    const val FAMILY_SERIF = 1
    const val FAMILY_MONO = 2

    const val BG_SYSTEM = 0
    const val BG_SEPIA = 1
    const val BG_NIGHT = 2
    const val BG_WHITE = 3

    private fun prefs(context: Context) = IndexDiscovery.prefs(context)

    fun fontSp(context: Context) = prefs(context).getFloat(KEY_FONT_SP, 17f)
    fun setFontSp(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_FONT_SP, value).apply()

    fun lineHeight(context: Context) = prefs(context).getFloat(KEY_LINE, 1.7f)
    fun setLineHeight(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_LINE, value).apply()

    fun family(context: Context) = prefs(context).getInt(KEY_FAMILY, FAMILY_SYSTEM)
    fun setFamily(context: Context, value: Int) = prefs(context).edit().putInt(KEY_FAMILY, value).apply()

    fun margin(context: Context) = prefs(context).getInt(KEY_MARGIN, 20)
    fun setMargin(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MARGIN, value).apply()

    fun background(context: Context) = prefs(context).getInt(KEY_BG, BG_SYSTEM)
    fun setBackground(context: Context, value: Int) = prefs(context).edit().putInt(KEY_BG, value).apply()
}
