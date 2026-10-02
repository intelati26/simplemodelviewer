package io.github.intelati26.simplemodelview

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import simplemodelview.Http
import simplemodelview.HttpResult
import simplemodelview.userAgent
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The app's only network client. Be a good client of the servers: a descriptive User-Agent, a small cap on parallel
 * requests, and a disk cache. Chunks of one cycle (and MRMS scan files) never change, so they are cached whatever the server's
 * headers say; only the `.zgroup` probes (does a newer cycle exist yet?) and the MRMS listings are always fresh.
 */
class OkHttpHttp(context: Context, cacheMegabytes: Long = 150, private val contact: () -> String = { "" }) : Http {
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), cacheMegabytes * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent(USER_AGENT, contact())).build())
        })
        .addNetworkInterceptor(Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val url = response.request.url.toString()
            // an MRMS scan file never changes either (its name carries the scan time)
            if (response.isSuccessful && (".zarr/" in url && !url.endsWith("/.zgroup") || "noaa-mrms-pds" in url && url.endsWith(".grib2.gz"))) {
                response.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=604800").build()
            } else response
        })
        .build().also { it.dispatcher.maxRequestsPerHost = 4 }

    override suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                HttpResult(response.code, response.body?.bytes() ?: ByteArray(0), response.headers.getDate("Last-Modified")?.time?.div(1000))
            }
        } catch (e: IOException) {
            HttpResult(0, ByteArray(0))
        }
    }

    companion object {
        // the contact address is set by the user in Settings and appended to this per request (see simplemodelview.userAgent)
        const val USER_AGENT = "simplemodelview/0.1 (unofficial viewer; +https://github.com/intelati26/simplemodelviewer;)"
    }
}
