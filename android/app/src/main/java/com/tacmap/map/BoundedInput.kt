package com.tacmap.map

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Read at most [limit] bytes plus one sentinel byte, compatible with API 26. */
internal fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit + 1, 32 * 1024))
    val buffer = ByteArray(16 * 1024)
    var remaining = limit + 1
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}

private val lookupHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

/** Cancellable, size-bounded HTTPS GET used by coordinate lookup services. */
internal suspend fun boundedHttpsGet(url: String, limit: Int): String? {
    if (!url.startsWith("https://")) return null
    return suspendCancellableCoroutine { continuation ->
        val call = lookupHttpClient.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val text = runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body ?: return@use null
                        if (body.contentLength() > limit) return@use null
                        val bytes = body.byteStream().use { it.readBounded(limit) }
                        if (bytes.size > limit) null else bytes.toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                if (continuation.isActive) continuation.resume(text)
            }
        })
    }
}
