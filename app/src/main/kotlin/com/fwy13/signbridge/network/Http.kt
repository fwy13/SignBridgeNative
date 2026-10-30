package com.fwy13.signbridge.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpFailure(val status: Int, message: String) : IOException(message)
class Http {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    suspend fun text(url: String, method: String = "GET", body: JSONObject? = null, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
            val payload = if (method in listOf("GET", "HEAD")) null else (body?.toString() ?: "").toRequestBody("application/json".toMediaType())
            this.method(method, payload)
        }.build()
        return execute(request)
    }
    suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val json = runCatching { JSONObject(text) }.getOrNull()
                            throw HttpFailure(it.code, json?.optString("msg")?.takeIf(String::isNotBlank)
                                ?: json?.optString("error_description")?.takeIf(String::isNotBlank)
                                ?: json?.optString("detail")?.takeIf(String::isNotBlank) ?: "HTTP ${it.code}")
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        })
    }
}
