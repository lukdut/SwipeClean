package com.lukdut.swipeclean.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.lukdut.swipeclean.BuildConfig
import com.lukdut.swipeclean.data.model.EmbeddingModelRepository
import com.lukdut.swipeclean.data.model.InstalledModel
import com.lukdut.swipeclean.data.model.ModelManifest
import com.lukdut.swipeclean.data.model.ModelResponse
import com.lukdut.swipeclean.data.model.ModelSpec
import com.lukdut.swipeclean.data.model.ModelTransport

/** Optional fixture in the test APK: python3 tools/fetch_embedding_model.py. No test uses the network. */
object TestModelFixture {
    fun spec(context: Context): ModelSpec = context.assets.open(ModelManifest.ASSET).bufferedReader().use {
        ModelManifest.decode(it.readText(), BuildConfig.VERSION_CODE)
    }

    suspend fun install(context: Context): InstalledModel =
        EmbeddingModelRepository.getInstance(context).prepareForAnalysis(transport = ModelTransport {
            val input = InstrumentationRegistry.getInstrumentation().context.assets
                .open("siglip2-base-patch16-224-int8.onnx")
            object : ModelResponse {
                override val stream = input
                override fun close() = input.close()
            }
        })
}
