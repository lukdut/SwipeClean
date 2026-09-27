package com.lukdut.swipeclean.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ModelStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "models-test").toFile()
    private val bytes = ByteArray(512 * 1024) { (it % 127).toByte() }
    private val spec = TestModelFixture.spec(context).copy(version = "test-v1", sizeBytes = bytes.size.toLong(),
        sha256 = ModelSpec.sha256(bytes))
    private val store = ModelStore(directory, 1) { require(it.file.readBytes().contentEquals(bytes)) }

    @After fun cleanUp() { directory.deleteRecursively() }

    private fun transport(data: ByteArray) = ModelTransport {
        object : ModelResponse {
            override val stream = ByteArrayInputStream(data)
            override fun close() = stream.close()
        }
    }

    @Test fun installsOnlyAfterValidationAndRestoresOfflineAfterRecreation() = runBlocking {
        val progress = mutableListOf<ModelDownloadProgress>()
        var committed = false
        val installed = store.install(spec, null, transport(bytes), { progress += it }) { committed = true }
        assertTrue(committed)
        assertEquals(spec, installed.spec)
        assertEquals(spec, ModelStore(directory, 1) { }.restore()!!.spec)
        assertEquals(ModelDownloadStage.DOWNLOADING, progress.first().stage)
        assertEquals(ModelDownloadStage.VALIDATING, progress.last().stage)
        assertEquals(spec.sizeBytes, progress.last().receivedBytes)
        assertTrue(directory.listFiles()!!.none { it.extension == "part" })
    }

    @Test fun truncatedOversizedAndWrongChecksumDownloadsKeepTheActiveModel() = runBlocking {
        val installed = store.install(spec, null, transport(bytes), {})
        val replacement = spec.copy(version = "v2", inputSize = 384)
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1), ByteArray(bytes.size) { 12 })) {
            try {
                store.install(replacement, installed, transport(bad), {})
                fail("Corrupted download was accepted")
            } catch (_: ModelInstallException) { }
            assertEquals(spec, store.restore()!!.spec)
            assertArrayEquals(bytes, installed.file.readBytes())
        }
    }

    @Test fun cancellationDiscardsPartialDownloadAndRetrySucceeds() = runBlocking {
        val installed = store.install(spec, null, transport(bytes), {})
        val replacement = spec.copy(version = "v2", dimensions = 512)
        try {
            store.install(replacement, installed, transport(bytes), {
                if (it.receivedBytes > 0) throw CancellationException("User cancelled")
            })
            fail("Download was not cancelled")
        } catch (_: CancellationException) { }
        assertEquals(spec, store.restore()!!.spec)
        assertTrue(directory.listFiles()!!.none { it.extension == "part" })
        assertEquals(replacement, store.install(replacement, installed, transport(bytes), {}).spec)
        assertEquals(replacement, store.restore()!!.spec)
    }

    @Test fun incompatibleOnnxNeverReplacesAWorkingModel() = runBlocking {
        val installed = store.install(spec, null, transport(bytes), {})
        val replacement = spec.copy(version = "v2", inputSize = 384)
        val rejecting = ModelStore(directory, 1) { if (it.spec == replacement) throw IOException("Unsupported ops") }
        try {
            rejecting.install(replacement, installed, transport(bytes), {})
            fail("Incompatible model was accepted")
        } catch (_: ModelInstallException) { }
        assertEquals(spec, store.restore()!!.spec)
    }

    @Test fun damagedActiveModelRollsBackToPreviousVersionOnRestart() = runBlocking {
        val first = store.install(spec, null, transport(bytes), {})
        val replacement = spec.copy(version = "v2", inputSize = 384)
        val second = store.install(replacement, first, transport(bytes), {})
        second.file.writeBytes(byteArrayOf(0))
        assertEquals(spec, store.restore()!!.spec)
        assertEquals(spec, ModelStore(directory, 1) { }.restore()!!.spec)
    }

    @Test fun unsupportedRuntimeAfterRestartAlsoFallsBack() = runBlocking {
        val first = store.install(spec, null, transport(bytes), {})
        val replacement = spec.copy(version = "v2", inputSize = 384)
        store.install(replacement, first, transport(bytes), {})
        val changedRuntime = ModelStore(directory, 1) { if (it.spec == replacement) error("Unsupported model") }
        assertEquals(spec, changedRuntime.restore()!!.spec)
    }

    @Test fun orphanDownloadsAreIgnoredAndOnlyTwoInstalledVersionsAreRetained() = runBlocking {
        File(directory, "orphan.part").writeBytes(bytes)
        assertNull(store.restore())
        val first = store.install(spec, null, transport(bytes), {})
        val second = store.install(spec.copy(inputSize = 384), first, transport(bytes), {})
        val third = store.install(spec.copy(inputSize = 512), second, transport(bytes), {})
        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
        assertTrue(third.file.exists())
        assertEquals(2, directory.listFiles()!!.count { it.extension == "onnx" })
    }

    @Test fun manifestRoundTripsAndRejectsUnsupportedContracts() {
        assertEquals(spec, ModelManifest.decode(ModelManifest.encode(spec), 1))
        for (invalid in listOf(spec.copy(schemaVersion = 2), spec.copy(minAppVersion = 2),
            spec.copy(preprocessing = "new-algorithm"), spec.copy(url = "http://example.com/model"))) {
            assertThrows(IllegalArgumentException::class.java) {
                ModelManifest.decode(ModelManifest.encode(invalid), 1)
            }
        }
    }
}
