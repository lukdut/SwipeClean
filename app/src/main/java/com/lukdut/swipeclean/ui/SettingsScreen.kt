package com.lukdut.swipeclean.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lukdut.swipeclean.analysis.AnalysisProgress
import com.lukdut.swipeclean.data.PhotoSettings
import com.lukdut.swipeclean.data.Priority
import com.lukdut.swipeclean.data.QualitySignal
import com.lukdut.swipeclean.data.SortOrder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    settings: PhotoSettings,
    analysis: AnalysisProgress,
    hasAnalysisResults: Boolean,
    progressReset: ProgressResetState,
    isLoading: Boolean,
    onSortOrder: (SortOrder) -> Unit,
    onPriority: (QualitySignal, Priority) -> Unit,
    onStartAnalysis: () -> Unit,
    onPauseAnalysis: () -> Unit,
    onResetProgress: () -> Unit,
    onBack: () -> Unit,
    openAtAnalysis: Boolean = false
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    val analysisButtonRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var analysisButtonPlaced by remember { mutableStateOf(false) }
    var initialScrollHandled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openAtAnalysis, analysisButtonPlaced) {
        if (openAtAnalysis && analysisButtonPlaced && !initialScrollHandled) {
            analysisButtonRequester.bringIntoView()
            initialScrollHandled = true
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Сбросить прогресс просмотра?") },
            text = {
                Text("Все решения «оставить» и «удалить» будут сброшены, а корзина приложения очистится. Фотографии снова появятся в очереди. Настройки и результаты анализа сохранятся.")
            },
            confirmButton = {
                TextButton(
                    enabled = !isLoading && !progressReset.running,
                    onClick = {
                        showResetConfirmation = false
                        onResetProgress()
                    }
                ) { Text("Сбросить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Порядок фотографий")
                Column(Modifier.selectableGroup()) {
                    SortOrder.entries.forEach { order ->
                        val needsAnalysis = order == SortOrder.ByPotentiallyUnwanted && !hasAnalysisResults
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.sortOrder == order,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (needsAnalysis) {
                                            coroutineScope.launch { analysisButtonRequester.bringIntoView() }
                                        } else onSortOrder(order)
                                    }
                                )
                                .semantics {
                                    if (needsAnalysis) stateDescription = "Сначала выполните анализ ниже"
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = settings.sortOrder == order,
                                onClick = null
                            )
                            Text(order.label, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            AnalysisCard(
                analysis, isLoading, onStartAnalysis, onPauseAnalysis,
                actionModifier = Modifier
                    .bringIntoViewRequester(analysisButtonRequester)
                    .onGloballyPositioned { analysisButtonPlaced = true }
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Приоритет признаков")
                Text(
                    "Учитываются в режиме «Неудачные». Изменения применяются сразу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!settings.hasEnabledSignals) {
                    Text("Все признаки выключены — фотографии идут по дате.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                QualitySignal.entries.forEach { signal ->
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(signal.label, style = MaterialTheme.typography.titleMedium)
                            Text(signal.description, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Priority.entries.forEach { priority ->
                                    FilterChip(
                                        selected = settings.priority(signal) == priority,
                                        onClick = { onPriority(signal, priority) },
                                        label = {
                                            Text(priority.label, modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center)
                                        },
                                        modifier = Modifier.weight(1f).semantics {
                                            contentDescription = "${signal.label}: ${priority.label}"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Оценка приблизительная: ночные и художественные снимки тоже могут оказаться в начале очереди. Решение об удалении принимаете вы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Прогресс просмотра")
                Text(
                    "Начните разбор заново: сбросьте решения по фотографиям и очистите корзину приложения. Сами фотографии останутся на устройстве.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { showResetConfirmation = true },
                    enabled = !isLoading && !progressReset.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (progressReset.running) "Сбрасываем прогресс…" else "Сбросить прогресс")
                }
                if (progressReset.completed) {
                    Text("Прогресс сброшен. Можно начать просмотр заново.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                progressReset.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun AnalysisCard(
    analysis: AnalysisProgress,
    isLoading: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer
    )) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Анализ качества", style = MaterialTheme.typography.titleMedium)
            Text(
                "Запускается только вручную и может расходовать заряд. Продолжается при сворачивании приложения и выключенном экране. Приостановить можно здесь или в уведомлении. Результаты сохраняются на устройстве.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when {
                    analysis.stopping -> "Сохраняем результаты…"
                    analysis.preparing -> "Подготавливаем фотографии…"
                    isLoading -> "Загружаем список фотографий…"
                    analysis.total == 0 -> "Нет фотографий для анализа"
                    analysis.analyzed == analysis.total -> "Все фото в очереди проанализированы"
                    analysis.running -> "Анализируем: ${analysis.analyzed} из ${analysis.total}"
                    else -> "Проанализировано: ${analysis.analyzed} из ${analysis.total}"
                },
                style = MaterialTheme.typography.labelLarge
            )
            if (analysis.total > 0) {
                LinearProgressIndicator(
                    progress = { (analysis.analyzed + analysis.skipped).toFloat() / analysis.total },
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (analysis.skipped > 0) {
                Text("Не удалось прочитать: ${analysis.skipped}. Их можно проверить повторно.",
                    style = MaterialTheme.typography.bodySmall)
            }
            analysis.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Box(actionModifier.fillMaxWidth()) {
                if (analysis.running) {
                    Button(
                        onClick = onPause,
                        enabled = !analysis.stopping,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (analysis.stopping) "Приостанавливаем…" else "Приостановить")
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = !isLoading && analysis.total > analysis.analyzed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(when {
                            analysis.skipped > 0 && analysis.remaining == 0 -> "Повторить для пропущенных"
                            analysis.analyzed > 0 && analysis.remaining > 0 -> "Продолжить анализ"
                            else -> "Анализировать фото"
                        })
                    }
                }
            }
        }
    }
}
