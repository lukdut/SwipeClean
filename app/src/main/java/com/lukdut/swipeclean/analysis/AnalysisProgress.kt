package com.lukdut.swipeclean.analysis

import com.lukdut.swipeclean.data.model.ModelDownloadProgress

/** Counts refer to accessible photos present when this manual analysis pass started. */
data class AnalysisProgress(
    val total: Int = 0,
    val analyzed: Int = 0,
    val skipped: Int = 0,
    val running: Boolean = false,
    val stopping: Boolean = false,
    val preparing: Boolean = false,
    val error: String? = null,
    val modelDownload: ModelDownloadProgress? = null,
    val modelVersion: String? = null
) {
    val remaining: Int get() = (total - analyzed - skipped).coerceAtLeast(0)
}
