package org.spcrefs.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import spcrefs.Http
import spcrefs.HttpResult
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The app's only network client. Be a good client of SPC's server: a descriptive User-Agent, a small cap on parallel
 * requests, and a disk cache. Chunks of one cycle never change, so they are cached whatever the server's headers say; only
 * the `.zgroup` probes (does a newer cycle exist yet?) are always fresh.
 */
class OkHttpHttp(context: Context, cacheMegabytes: Long = 150) : Http {
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), cacheMegabytes * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        })
        .addNetworkInterceptor(Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val url = response.request.url.toString()
            if (response.isSuccessful && ".zarr/" in url && !url.endsWith("/.zgroup")) {
                response.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=604800").build()
            } else response
        })
        .build().also { it.dispatcher.maxRequestsPerHost = 4 }

    override suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                HttpResult(response.code, response.body?.bytes() ?: ByteArray(0))
            }
        } catch (e: IOException) {
            HttpResult(0, ByteArray(0))
        }
    }

    companion object {
        // TODO(before release): put a real contact address / project URL here (see docs/outline.md, risk 2)
        const val USER_AGENT = "SPCRefsMobile/0.1 (unofficial viewer; +https://github.com/OWNER/spcrefs)"
    }
}
