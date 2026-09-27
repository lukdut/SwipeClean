package com.lukdut.swipeclean.data.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class InstalledModel(val spec: ModelSpec, val file: File)
enum class ModelDownloadStage { DOWNLOADING, VERIFYING, VALIDATING }
data class ModelDownloadProgress(
    val stage: ModelDownloadStage,
    val receivedBytes: Long,
    val totalBytes: Long
) {
    val fraction: Float get() = (receivedBytes.toDouble() / totalBytes.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    val label: String get() = when (stage) {
        ModelDownloadStage.DOWNLOADING -> "Скачиваем модель: ${receivedBytes / 1_048_576} из ${(totalBytes + 1_048_575) / 1_048_576} МиБ"
        ModelDownloadStage.VERIFYING -> "Проверяем загрузку…"
        ModelDownloadStage.VALIDATING -> "Подготавливаем модель…"
    }
}

class ModelInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Called under the repository mutex. Only the final atomic state-file replacement activates a model. */
class ModelStore(
    private val directory: File,
    private val appVersion: Int,
    private val validateModel: (InstalledModel) -> Unit
) {
    private val stateFile get() = File(directory, "installed.json")
    private fun modelFile(spec: ModelSpec) = File(directory, "${spec.embeddingVersion}.onnx")

    suspend fun restore(): InstalledModel? = withContext(Dispatchers.IO) {
        directory.listFiles()?.filter { it.extension == "part" || it.name == "installed.json.tmp" }
            ?.forEach { it.delete() }
        val (active, previous) = readState()
        for (spec in listOfNotNull(active, previous)) {
            val installed = InstalledModel(spec, modelFile(spec))
            try {
                verifyFile(installed)
                validateModel(installed)
                currentCoroutineContext().ensureActive()
                if (spec != active) writeState(spec, null)
                return@withContext installed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed update or corrupted file must not discard the last working model.
            }
        }
        null
    }

    suspend fun install(
        spec: ModelSpec,
        previous: InstalledModel?,
        transport: ModelTransport,
        onProgress: suspend (ModelDownloadProgress) -> Unit,
        onInstalled: (InstalledModel) -> Unit = {}
    ): InstalledModel = withContext(Dispatchers.IO) {
        spec.validate(appVersion)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "${spec.embeddingVersion}.part")
        val installed = InstalledModel(spec, modelFile(spec))
        try {
            temporary.delete()
            if (directory.usableSpace < spec.sizeBytes + 8 * 1024 * 1024) {
                throw ModelInstallException("Недостаточно места для модели. Освободите память и повторите загрузку.")
            }
            var received = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            onProgress(ModelDownloadProgress(ModelDownloadStage.DOWNLOADING, received, spec.sizeBytes))
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            transport.open(spec.url).use { response ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var reported = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val count = response.stream.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > spec.sizeBytes) throw ModelInstallException("Размер модели не совпал. Повторите загрузку позже.")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (received - reported >= 256 * 1024 || received == spec.sizeBytes) {
                            onProgress(ModelDownloadProgress(ModelDownloadStage.DOWNLOADING, received, spec.sizeBytes))
                            reported = received
                        }
                    }
                    output.fd.sync()
                }
            }
            coroutine.ensureActive()
            onProgress(ModelDownloadProgress(ModelDownloadStage.VERIFYING, received, spec.sizeBytes))
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (received != spec.sizeBytes || actual != spec.sha256) {
                throw ModelInstallException("Файл модели повреждён или загружен не полностью. Повторите загрузку.")
            }
            onProgress(ModelDownloadProgress(ModelDownloadStage.VALIDATING, received, spec.sizeBytes))
            try {
                validateModel(InstalledModel(spec, temporary))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ModelInstallException("Модель несовместима с приложением. Проверьте обновления модели.", e)
            }
            coroutine.ensureActive()
            atomicMove(temporary, installed.file)
            writeState(spec, previous?.spec?.takeIf { it.embeddingVersion != spec.embeddingVersion })
            // Publish in the same non-suspending commit section, including if cancellation arrives
            // immediately afterwards while withContext is returning to the service.
            onInstalled(installed)
            // Keep the active model and one rollback copy. Stale partial files are never activated.
            val keep = setOf(installed.file.name, previous?.file?.name, stateFile.name)
            directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            installed
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelInstallException) {
            throw e
        } catch (e: Exception) {
            throw ModelInstallException("Не удалось скачать модель. Проверьте интернет и повторите загрузку.", e)
        } finally {
            temporary.delete()
        }
    }

    private suspend fun verifyFile(model: InstalledModel) {
        if (model.file.length() != model.spec.sizeBytes) throw IOException("Wrong model size")
        val digest = MessageDigest.getInstance("SHA-256")
        model.file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == model.spec.sha256)
    }

    private fun readState(): Pair<ModelSpec?, ModelSpec?> {
        if (!stateFile.exists()) return null to null
        return try {
            require(stateFile.length() <= ModelManifest.MAX_BYTES * 2)
            val json = JSONObject(stateFile.readText())
            fun spec(key: String): ModelSpec? = try {
                json.optJSONObject(key)?.let { ModelManifest.decode(it.toString(), appVersion) }
            } catch (_: Exception) { null }
            spec("active") to spec("previous")
        } catch (_: Exception) { null to null }
    }

    private fun writeState(active: ModelSpec, previous: ModelSpec?) {
        val json = JSONObject().put("active", JSONObject(ModelManifest.encode(active)))
        if (previous != null) json.put("previous", JSONObject(ModelManifest.encode(previous)))
        val temporary = File(directory, "installed.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        atomicMove(temporary, stateFile)
    }

    private fun atomicMove(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }
}
