package com.lukdut.swipeclean.data.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.lukdut.swipeclean.BuildConfig
import com.lukdut.swipeclean.data.PhotoEmbeddingExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ModelState(
    val initializing: Boolean = true,
    val installed: InstalledModel? = null,
    val bundled: ModelSpec? = null,
    val available: ModelSpec? = null,
    val checking: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val downloadSizeBytes: Long get() = (available ?: bundled)?.sizeBytes ?: 0
}

class EmbeddingModelRepository private constructor(context: Context) {
    private val assets = context.assets
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ModelState())
    val state = _state.asStateFlow()
    private val store = ModelStore(File(context.noBackupFilesDir, "embedding-models"),
        BuildConfig.VERSION_CODE, ::validateModel)

    suspend fun initialize() {
        // Reading an already installed model (for example before deletion) must not wait for
        // an unrelated update check that currently holds the mutex during a network request.
        if (!_state.value.initializing) return
        mutex.withLock { initializeLocked() }
    }

    private suspend fun initializeLocked() {
        if (!_state.value.initializing) return
        val bundled = withContext(Dispatchers.IO) {
            assets.open(ModelManifest.ASSET).bufferedReader().use {
                ModelManifest.decode(it.readText(), BuildConfig.VERSION_CODE)
            }
        }
        val installed = store.restore()
        _state.value = ModelState(initializing = false, installed = installed, bundled = bundled)
    }

    /** Only checks metadata. Downloading or activating weights requires a separate user action. */
    suspend fun checkForUpdate() = mutex.withLock {
        initializeLocked()
        _state.update { it.copy(checking = true, message = null, error = null) }
        try {
            val spec = withContext(Dispatchers.IO) {
                val bytes = HttpsModelTransport.open(BuildConfig.MODEL_MANIFEST_URL).use { response ->
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = response.stream.read(buffer)
                        if (count < 0) break
                        require(result.size() + count <= ModelManifest.MAX_BYTES)
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
                ModelManifest.decode(bytes.toString(Charsets.UTF_8), BuildConfig.VERSION_CODE)
            }
            val update = spec.takeIf { it.embeddingVersion != _state.value.installed?.spec?.embeddingVersion }
            _state.update { it.copy(available = update, message = if (update == null)
                "Установлена актуальная модель." else "Доступна модель: ${spec.displayName}.") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelCompatibilityException) {
            _state.update { it.copy(available = null, error = e.message) }
        } catch (_: Exception) {
            _state.update { it.copy(error = "Не удалось проверить обновление модели. Попробуйте позже.") }
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    /** Existing models never require a network request. A pass keeps the returned model throughout. */
    suspend fun prepareForAnalysis(
        update: Boolean = false,
        transport: ModelTransport = HttpsModelTransport,
        onProgress: suspend (ModelDownloadProgress) -> Unit = {}
    ): InstalledModel = mutex.withLock {
        initializeLocked()
        val current = _state.value.installed
        if (current != null && !update) return@withLock current
        val spec = _state.value.available ?: checkNotNull(_state.value.bundled)
        if (current?.spec?.embeddingVersion == spec.embeddingVersion) return@withLock current
        store.install(spec, current, transport, onProgress) { installed ->
            _state.update { it.copy(installed = installed, available = null, error = null,
                message = if (current == null) "Модель готова. Анализ доступен без интернета."
                    else "Модель обновлена. Признаки фотографий будут рассчитаны заново.") }
        }
    }

    companion object {
        @Volatile private var instance: EmbeddingModelRepository? = null

        fun getInstance(context: Context): EmbeddingModelRepository = instance ?: synchronized(this) {
            instance ?: EmbeddingModelRepository(context.applicationContext).also { instance = it }
        }

        private fun validateModel(model: InstalledModel) {
            PhotoEmbeddingExtractor(model).use { extractor ->
                val bitmap = Bitmap.createBitmap(model.spec.inputSize, model.spec.inputSize, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    extractor.extract(bitmap)
                } finally { bitmap.recycle() }
            }
        }
    }
}
