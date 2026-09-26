package com.lukdut.swipeclean

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lukdut.swipeclean.ui.AppViewModel
import com.lukdut.swipeclean.ui.SwipeScreen
import com.lukdut.swipeclean.ui.SettingsScreen
import com.lukdut.swipeclean.ui.TrashScreen
import com.lukdut.swipeclean.ui.theme.SwipeCleanTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private var analysisShortcut by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAnalysisShortcut(intent)
        enableEdgeToEdge()

        setContent {
            SwipeCleanTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showTrash by rememberSaveable { mutableStateOf(false) }
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    var openSettingsAtAnalysis by rememberSaveable { mutableStateOf(false) }
                    var permissionGranted by remember { mutableStateOf(false) }
                    var permissionDenied by remember { mutableStateOf(false) }

                    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { results ->
                        if (results.values.any { it }) {
                            permissionGranted = true
                            viewModel.loadPhotos()
                        } else {
                            permissionDenied = true
                        }
                    }

                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) {
                        // Android permits foreground work even if notification permission is denied.
                        viewModel.startAnalysis()
                    }

                    LaunchedEffect(analysisShortcut) {
                        if (analysisShortcut > 0) {
                            showTrash = false
                            showSettings = true
                            openSettingsAtAnalysis = true
                        }
                    }

                    // Handle MediaStore delete request (API 30+)
                    val deleteLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartIntentSenderForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            viewModel.onDeleteCompleted()
                        } else {
                            viewModel.clearDeleteRequest()
                        }
                    }

                    val pendingDeleteSender by viewModel.pendingDeleteSender.collectAsState()
                    val settings by viewModel.settings.collectAsState()
                    val analysis by viewModel.analysisProgress.collectAsState()
                    val hasAnalysisResults by viewModel.hasAnalysisResults.collectAsState()
                    val progressReset by viewModel.progressReset.collectAsState()
                    val isLoading by viewModel.isLoading.collectAsState()
                    BackHandler(enabled = showTrash || showSettings) {
                        showTrash = false
                        showSettings = false
                        openSettingsAtAnalysis = false
                    }
                    LaunchedEffect(pendingDeleteSender) {
                        pendingDeleteSender?.let { sender ->
                            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                    }

                    // Check / request permissions on first launch
                    LaunchedEffect(Unit) {
                        val allGranted = requiredPermissions.all {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                        if (allGranted) {
                            permissionGranted = true
                            viewModel.loadPhotos()
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    }

                    when {
                        permissionDenied -> PermissionDeniedScreen(
                            onRetry = {
                                permissionDenied = false
                                permissionLauncher.launch(requiredPermissions)
                            }
                        )
                        !permissionGranted -> {
                            // Waiting for permission dialog — show nothing (or a brief splash)
                        }
                        showSettings -> SettingsScreen(
                            settings = settings,
                            analysis = analysis,
                            hasAnalysisResults = hasAnalysisResults,
                            openAtAnalysis = openSettingsAtAnalysis,
                            progressReset = progressReset,
                            isLoading = isLoading,
                            onSortOrder = viewModel::setSortOrder,
                            onPriority = viewModel::setPriority,
                            onStartAnalysis = {
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else viewModel.startAnalysis()
                            },
                            onPauseAnalysis = viewModel::pauseAnalysis,
                            onResetProgress = viewModel::resetProgress,
                            onBack = {
                                showSettings = false
                                openSettingsAtAnalysis = false
                            }
                        )
                        showTrash -> TrashScreen(
                            viewModel = viewModel,
                            onBack = { showTrash = false }
                        )
                        else -> SwipeScreen(
                            viewModel = viewModel,
                            onOpenTrash = { showTrash = true },
                            onOpenSettings = {
                                openSettingsAtAnalysis = false
                                showSettings = true
                            },
                            onOpenAnalysis = {
                                openSettingsAtAnalysis = true
                                showSettings = true
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnalysisShortcut(intent)
    }

    private fun handleAnalysisShortcut(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ANALYSIS, false) == true) {
            analysisShortcut++
            intent.removeExtra(EXTRA_OPEN_ANALYSIS)
        }
    }

    companion object {
        const val EXTRA_OPEN_ANALYSIS = "open_analysis"
    }
}

@androidx.compose.runtime.Composable
private fun PermissionDeniedScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Доступ к фото запрещён",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Приложению необходим доступ к галерее для просмотра и удаления фото.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Предоставить доступ")
        }
    }
}
