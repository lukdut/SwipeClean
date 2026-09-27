package com.lukdut.swipeclean.data.model

import java.net.URI
import java.security.MessageDigest

class ModelCompatibilityException(message: String) : IllegalArgumentException(message)

/** The supported encoder contract. New preprocessing algorithms require an app update. */
data class ModelSpec(
    val version: String,
    val displayName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val inputName: String,
    val outputName: String,
    val inputSize: Int,
    val dimensions: Int,
    val mean: List<Float>,
    val std: List<Float>,
    val preprocessing: String = PREPROCESSING,
    val minAppVersion: Int = 1,
    val schemaVersion: Int = 1
) {
    fun validate(appVersion: Int) {
        if (schemaVersion != 1 || minAppVersion > appVersion) {
            throw ModelCompatibilityException("Для новой модели нужно обновить приложение.")
        }
        require(minAppVersion >= 1)
        if (preprocessing != PREPROCESSING) {
            throw ModelCompatibilityException("Способ обработки новой модели пока не поддерживается приложением.")
        }
        require(version.matches(Regex("[A-Za-z0-9._-]{1,100}")) && displayName.length in 1..100)
        requireHttps(url)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(sizeBytes in 1..MAX_MODEL_BYTES)
        require(inputName.length in 1..100 && outputName.length in 1..100)
        require(inputSize in 16..1024 && dimensions in 2..MAX_DIMENSIONS)
        require(mean.size == 3 && std.size == 3)
        require(mean.all { it.isFinite() && it in -10f..10f })
        require(std.all { it.isFinite() && it in 0.0001f..10f })
    }

    // Bind cached vectors to weights AND all preprocessing/output parameters, even if a publisher
    // accidentally reuses a release version. URLs and display names do not affect feature identity.
    val embeddingVersion: String by lazy {
        val contract = listOf(sha256, preprocessing, inputName, outputName, inputSize, dimensions,
            mean.joinToString(","), std.joinToString(","), "vectorq8-v2").joinToString("|")
        "onnx-${sha256(contract.toByteArray(Charsets.UTF_8))}"
    }

    companion object {
        const val PREPROCESSING = "rgb-bilinear-nchw-v1"
        const val MAX_DIMENSIONS = 4096
        const val MAX_MODEL_BYTES = 512L * 1024 * 1024

        fun requireHttps(url: String) {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.fragment == null) { "Для загрузки модели требуется HTTPS." }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
