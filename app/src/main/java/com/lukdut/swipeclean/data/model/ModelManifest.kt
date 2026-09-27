package com.lukdut.swipeclean.data.model

import org.json.JSONArray
import org.json.JSONObject

object ModelManifest {
    const val ASSET = "models/embedding-model.json"
    const val MAX_BYTES = 64 * 1024

    fun decode(text: String, appVersion: Int): ModelSpec {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(text)
        fun floats(key: String): List<Float> = json.getJSONArray(key).let { array ->
            List(array.length()) { array.getDouble(it).toFloat() }
        }
        return ModelSpec(
            schemaVersion = json.getInt("schemaVersion"),
            minAppVersion = json.getInt("minAppVersion"),
            version = json.getString("version"), displayName = json.getString("displayName"),
            url = json.getString("url"), sha256 = json.getString("sha256"),
            sizeBytes = json.getLong("sizeBytes"), preprocessing = json.getString("preprocessing"),
            inputName = json.getString("inputName"), outputName = json.getString("outputName"),
            inputSize = json.getInt("inputSize"), dimensions = json.getInt("dimensions"),
            mean = floats("mean"), std = floats("std")
        ).also { it.validate(appVersion) }
    }

    fun encode(spec: ModelSpec): String = JSONObject().apply {
        put("schemaVersion", spec.schemaVersion)
        put("minAppVersion", spec.minAppVersion)
        put("version", spec.version)
        put("displayName", spec.displayName)
        put("url", spec.url)
        put("sha256", spec.sha256)
        put("sizeBytes", spec.sizeBytes)
        put("preprocessing", spec.preprocessing)
        put("inputName", spec.inputName)
        put("outputName", spec.outputName)
        put("inputSize", spec.inputSize)
        put("dimensions", spec.dimensions)
        put("mean", JSONArray(spec.mean))
        put("std", JSONArray(spec.std))
    }.toString()
}
