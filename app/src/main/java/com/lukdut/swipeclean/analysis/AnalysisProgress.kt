package com.lukdut.swipeclean.analysis

/** Counts refer to the unreviewed photos present when this analysis pass started. */
data class AnalysisProgress(
    val total: Int = 0,
    val analyzed: Int = 0,
    val skipped: Int = 0,
    val running: Boolean = false,
    val stopping: Boolean = false,
    val preparing: Boolean = false,
    val error: String? = null
) {
    val remaining: Int get() = (total - analyzed - skipped).coerceAtLeast(0)
}
