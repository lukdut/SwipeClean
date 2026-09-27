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
import androidx.compose.runtime.withFrameNanos
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
import com.lukdut.swipeclean.data.model.ModelState
import com.lukdut.swipeclean.data.model.ModelDownloadStage
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
    openAtAnalysis: Boolean = false,
    feedbackCount: Int = 0,
    feedbackReset: ProgressResetState = ProgressResetState(),
    onPersonalization: (Priority) -> Unit = {},
    onForgetFeedback: () -> Unit = {},
    modelState: ModelState = ModelState(),
    onCheckModelUpdate: () -> Unit = {},
    onUpdateModel: () -> Unit = {}
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showForgetConfirmation by rememberSaveable { mutableStateOf(false) }
    val analysisButtonRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var analysisButtonPlaced by remember { mutableStateOf(false) }
    var initialScrollHandled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openAtAnalysis, analysisButtonPlaced, modelState.initializing) {
        if (openAtAnalysis && analysisButtonPlaced && !modelState.initializing && !initialScrollHandled) {
            // Restoring a model changes the card height; scroll after that layout is measured.
            withFrameNanos { }
            analysisButtonRequester.bringIntoView()
            initialScrollHandled = true
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Сбросить прогресс просмотра?") },
            text = {
                Text("Прогресс просмотра будет сброшен, а корзина приложения очистится. Фотографии снова появятся в очереди. Настройки, результаты анализа и история рекомендаций по оставленным и удалённым фото сохранятся.")
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

    if (showForgetConfirmation) {
        AlertDialog(
            onDismissRequest = { showForgetConfirmation = false },
            title = { Text("Очистить историю рекомендаций?") },
            text = { Text("Приложение начнёт учиться на новых решениях. Фотографии, корзина и прогресс просмотра сохранятся.") },
            confirmButton = {
                TextButton(onClick = { showForgetConfirmation = false; onForgetFeedback() }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showForgetConfirmation = false }) { Text("Отмена") } }
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
                modelState = modelState, onCheckModelUpdate = onCheckModelUpdate, onUpdateModel = onUpdateModel,
                actionModifier = Modifier
                    .bringIntoViewRequester(analysisButtonRequester)
                    .onGloballyPositioned { analysisButtonPlaced = true }
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Ваши решения")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Похожие фотографии", style = MaterialTheme.typography.titleMedium)
                        Text("В режиме «Неудачные» поднимаем фото, похожие на те, которые вы удаляете, и снижаем приоритет похожих на оставленные. Выключение убирает влияние истории на порядок фото.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Priority.entries.forEach { priority ->
                                FilterChip(selected = settings.personalization == priority,
                                    onClick = { onPersonalization(priority) },
                                    label = { Text(priority.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                    modifier = Modifier.weight(1f).semantics {
                                        contentDescription = "Похожие фотографии: ${priority.label}"
                                    })
                            }
                        }
                    }
                }
                Text(
                    if (feedbackCount < 3) "Пока мало примеров для рекомендаций. Выполните анализ и продолжайте сортировку."
                    else "Сохранено примеров: $feedbackCount. Учитываются только достаточно похожие снимки.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { showForgetConfirmation = true },
                    enabled = !isLoading && !feedbackReset.running, modifier = Modifier.fillMaxWidth()) {
                    Text("Очистить историю рекомендаций")
                }
                if (feedbackReset.completed) Text("История рекомендаций очищена.", style = MaterialTheme.typography.bodyMedium)
                feedbackReset.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

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
    modelState: ModelState,
    onCheckModelUpdate: () -> Unit,
    onUpdateModel: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer
    )) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Анализ фотографий", style = MaterialTheme.typography.titleMedium)
            Text(
                "Анализ содержимого и качества выполняется на устройстве и может расходовать заряд. Продолжается при сворачивании приложения и выключенном экране. Приостановить можно здесь или в уведомлении. Просмотренные фото тоже анализируются для рекомендаций.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when {
                    modelState.initializing -> "Проверяем модель на устройстве…"
                    modelState.installed == null -> "Для первого анализа нужно скачать модель — около ${modelSizeMiB(modelState.downloadSizeBytes)} МиБ. Затем она работает без интернета. Фотографии остаются на устройстве."
                    else -> "Модель ${modelState.installed.spec.displayName} установлена. Анализ доступен без интернета."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when {
                    analysis.stopping -> if (analysis.preparing) "Останавливаем загрузку…" else "Сохраняем результаты…"
                    analysis.modelDownload != null -> analysis.modelDownload.label
                    analysis.preparing -> "Подготавливаем фотографии…"
                    isLoading -> "Загружаем список фотографий…"
                    analysis.total == 0 -> "Нет фотографий для анализа"
                    analysis.completed -> if (analysis.skipped > 0) "Анализ завершён"
                        else "Все доступные фото проанализированы"
                    analysis.running -> "Анализируем: ${analysis.analyzed} из ${analysis.total}"
                    else -> "Проанализировано: ${analysis.analyzed} из ${analysis.total}"
                },
                style = MaterialTheme.typography.labelLarge
            )
            val download = analysis.modelDownload
            if (download != null) {
                if (download.stage == ModelDownloadStage.DOWNLOADING) {
                    LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (analysis.total > 0) {
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
                        enabled = !isLoading && !modelState.initializing && !modelState.checking &&
                            analysis.total > analysis.analyzed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(when {
                            modelState.installed == null && analysis.error != null -> "Повторить загрузку модели"
                            modelState.installed == null -> "Скачать модель и анализировать"
                            analysis.skipped > 0 && analysis.remaining == 0 -> "Повторить для пропущенных"
                            analysis.analyzed > 0 && analysis.remaining > 0 -> "Продолжить анализ"
                            else -> "Анализировать фото"
                        })
                    }
                }
            }
            if (modelState.available != null && modelState.installed != null) {
                Text("Доступна новая модель — около ${modelSizeMiB(modelState.available.sizeBytes)} МиБ. После обновления фотографии будут проанализированы заново.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onUpdateModel,
                    enabled = !isLoading && !analysis.running && !modelState.checking,
                    modifier = Modifier.fillMaxWidth()) { Text("Обновить и повторить анализ") }
            }
            TextButton(onClick = onCheckModelUpdate,
                enabled = !modelState.initializing && !modelState.checking && !analysis.running,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (modelState.checking) "Проверяем обновления…" else "Проверить обновление модели")
            }
            modelState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            modelState.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun modelSizeMiB(bytes: Long): Long = (bytes + 524_288) / 1_048_576
