package com.lukdut.swipeclean.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisProgressTest {
    @Test
    fun completedPassIncludesSkippedPhotos() {
        assertTrue(AnalysisProgress(total = 10, analyzed = 10).completed)
        assertTrue(AnalysisProgress(total = 10, analyzed = 8, skipped = 2).completed)
        assertTrue(AnalysisProgress(total = 10, skipped = 10).completed)
    }

    @Test
    fun unfinishedOrFailedPassIsNotCompleted() {
        val finished = AnalysisProgress(total = 10, analyzed = 8, skipped = 2)
        assertFalse(finished.copy(skipped = 1).completed)
        assertFalse(finished.copy(running = true).completed)
        assertFalse(finished.copy(stopping = true).completed)
        assertFalse(finished.copy(preparing = true).completed)
        assertFalse(finished.copy(error = "Не удалось сохранить результаты").completed)
        assertFalse(AnalysisProgress().completed)
    }
}
