package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.util.containsIgnoreCase
import com.hippo.ehviewer.download.DownloadsFilterMode
import kotlinx.serialization.Serializable

@Serializable
data class DownloadsFilterState(
    val mode: DownloadsFilterMode,
    val label: String?,
    val state: Int = -1,
    val keyword: String = "",
)

fun DownloadsFilterState.take(info: DownloadInfo): Boolean {
    // Split on whitespace, "$" (E-Hentai tag terminator), and "\"" (quoted tag wrapper)
    val tokens = keyword.trim().split(Regex("[\\s$\"]+")).filter { it.isNotEmpty() }
    return mode.take(info, label) &&
        (state == -1 || info.state == state) &&
        (tokens.isEmpty() || tokens.all { token ->
            // Strip namespace prefix for matching: "artist:tanaka" -> "tanaka"
            // This is needed because ComicInfo.xml (via toSimpleTags) stores most tags
            // as bare names without namespace prefix, while API-sourced tags retain
            // the full "namespace:tag" format.
            val tagOnly = token.substringAfter(':')
            val hasNamespace = tagOnly != token
            fun doMatch(tok: String) = info.title.containsIgnoreCase(tok) ||
                info.titleJpn.containsIgnoreCase(tok) ||
                info.simpleTags?.any { tag ->
                    tag.equals(tok, ignoreCase = true) || tag.containsIgnoreCase(tok)
                } == true
            doMatch(token) || (hasNamespace && doMatch(tagOnly))
        })
}
