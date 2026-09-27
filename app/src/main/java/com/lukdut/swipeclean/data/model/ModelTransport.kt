package com.lukdut.swipeclean.data.model

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

interface ModelResponse : Closeable {
    val stream: InputStream
}

/** Separate transport lets storage tests simulate truncated downloads and interruptions offline. */
fun interface ModelTransport {
    fun open(url: String): ModelResponse
}

object HttpsModelTransport : ModelTransport {
    override fun open(url: String): ModelResponse {
        var location = url
        repeat(6) {
            ModelSpec.requireHttps(location)
            val connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val input = connection.inputStream
                        return object : ModelResponse {
                            override val stream = input
                            override fun close() {
                                try { input.close() } finally { connection.disconnect() }
                            }
                        }
                    }
                    301, 302, 303, 307, 308 -> {
                        val next = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                        location = URL(URL(location), next).toString()
                        connection.disconnect()
                    }
                    else -> throw IOException("Model server returned HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
        }
        throw IOException("Too many model redirects")
    }
}
