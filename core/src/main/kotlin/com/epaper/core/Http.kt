package com.epaper.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/** Shared client: no automatic redirects (the resolver follows them itself to detect loops). */
fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        val req = chain.request()
        chain.proceed(
            if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", MOBILE_USER_AGENT).build() else req
        )
    }
    .build()

class HttpException(val code: Int, message: String) : IOException(message)

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * GET text with retries on network errors and 5xx/429 (exponential backoff).
 * 4xx other than 429 fails immediately: retrying will not help.
 */
suspend fun OkHttpClient.getText(url: String, attempts: Int = 3): String {
    var wait = 500L
    var last: IOException? = null
    repeat(attempts) { attempt ->
        try {
            newCall(Request.Builder().url(url).build()).await().use { r ->
                if (r.isSuccessful) return r.body?.string().orEmpty()
                val err = HttpException(r.code, "HTTP ${r.code} for $url")
                if (r.code != 429 && r.code < 500) throw err
                last = err
            }
        } catch (e: HttpException) {
            if (e.code != 429 && e.code < 500) throw e
            last = e
        } catch (e: IOException) {
            last = e
        }
        if (attempt < attempts - 1) { delay(wait); wait *= 2 }
    }
    throw last ?: IOException("Failed to fetch $url")
}
