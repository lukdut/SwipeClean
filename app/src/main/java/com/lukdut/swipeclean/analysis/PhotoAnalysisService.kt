package com.lukdut.swipeclean.analysis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lukdut.swipeclean.MainActivity
import com.lukdut.swipeclean.R
import com.lukdut.swipeclean.data.MediaStoreRepository
import com.lukdut.swipeclean.data.PhotoAnalyzer
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Started only by a visible user action; never restarted automatically by Android. */
class PhotoAnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var analysisJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopError: String? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Анализ фотографий", NotificationManager.IMPORTANCE_LOW
        ))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            AnalysisCoordinator.pause()
            if (analysisJob == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            if (analysisJob == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (analysisJob != null) return START_NOT_STICKY
        val request = intent.getLongExtra(EXTRA_REQUEST, -1)
        try {
            val type = when {
                Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
            val notification = notification(AnalysisCoordinator.progress.value ?: AnalysisProgress(preparing = true))
            // Use the platform API: older ServiceCompat implementations mask out mediaProcessing.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, type)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot promote photo analysis service", e)
            AnalysisCoordinator.finish(request, "Не удалось запустить фоновый анализ. Попробуйте ещё раз.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!AnalysisCoordinator.isRequested(request)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val job = scope.launch(start = CoroutineStart.LAZY) { analyze(request) }
        analysisJob = job
        AnalysisCoordinator.attach(request, job)
        job.start()
        return START_NOT_STICKY
    }

    @OptIn(FlowPreview::class)
    private suspend fun analyze(request: Long) {
        val updates = scope.launch {
            AnalysisCoordinator.progress.filterNotNull().sample(500).collect { progress ->
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                        this@PhotoAnalysisService, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED) {
                    notifications.notify(NOTIFICATION_ID, notification(progress))
                }
            }
        }
        var keepAwake: Job? = null
        var error: String? = null
        try {
            val lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SwipeClean:PhotoAnalysis")
                .apply { setReferenceCounted(false) }
            wakeLock = lock
            // A timeout also releases the lock if normal cleanup fails. Renew only during this run.
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            keepAwake = scope.launch {
                while (isActive) {
                    delay(WAKE_LOCK_TIMEOUT_MS / 2)
                    lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }

            val database = AppDatabase.getInstance(this)
            val dao = database.photoAnalysisDao()
            val reviews = database.photoReviewDao()
            val reviewed = (reviews.getIdsByStatus(PhotoReviewStatus.KEPT) +
                reviews.getIdsByStatus(PhotoReviewStatus.TRASH)).toHashSet()
            val candidates = MediaStoreRepository(this).loadAllPhotos().filter { it.id !in reviewed }
            val cached = dao.getAll().associateBy { it.mediaStoreId }
            val missing = candidates.filter { cached[it.id]?.matches(it) != true }
            AnalysisCoordinator.prepared(request, candidates.size, candidates.size - missing.size)
            val analyzer = PhotoAnalyzer(contentResolver)
            BatchedAnalysisRunner().run(
                items = missing,
                shouldStop = { AnalysisCoordinator.shouldStop(request) },
                analyze = { photo -> analyzer.analyze(photo)?.let { PhotoAnalysisEntity.from(photo, it) } },
                save = { batch ->
                    dao.upsertAll(batch)
                    AnalysisCoordinator.saved(request, batch.size)
                },
                onSkipped = { AnalysisCoordinator.skipped(request) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Photo analysis failed", e)
            error = "Не удалось завершить анализ. Можно продолжить с сохранённых результатов."
        } finally {
            updates.cancel()
            keepAwake?.cancel()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            analysisJob = null
            AnalysisCoordinator.finish(request, stopError ?: error)
            stopSelf()
        }
    }

    private fun notification(progress: AnalysisProgress): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_ANALYSIS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1,
            Intent(this, PhotoAnalysisService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = when {
            progress.stopping -> "Сохраняем результаты…"
            progress.preparing -> "Подготавливаем фотографии…"
            progress.skipped > 0 -> "${progress.analyzed} из ${progress.total} · пропущено ${progress.skipped}"
            else -> "${progress.analyzed} из ${progress.total}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_analysis_notification)
            .setContentTitle("Анализ фотографий")
            .setContentText(message)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(progress.total, progress.analyzed + progress.skipped, progress.preparing)
            .apply { if (!progress.stopping) addAction(0, "Пауза", pause) }
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopError = "Android остановил длительный анализ. Сохранённые результаты доступны; продолжить можно вручную."
        analysisJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        internal const val ACTION_START = "com.lukdut.swipeclean.analysis.START"
        internal const val ACTION_PAUSE = "com.lukdut.swipeclean.analysis.PAUSE"
        internal const val EXTRA_REQUEST = "analysis_request"
        internal const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "photo_analysis"
        private const val TAG = "PhotoAnalysisService"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
