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
    val tokens = keyword.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return mode.take(info, label) &&
        (state == -1 || info.state == state) &&
        (tokens.isEmpty() || tokens.all { token ->
            info.title.containsIgnoreCase(token) ||
                info.titleJpn.containsIgnoreCase(token) ||
                info.simpleTags?.any { it.containsIgnoreCase(token) } == true
        })
}
