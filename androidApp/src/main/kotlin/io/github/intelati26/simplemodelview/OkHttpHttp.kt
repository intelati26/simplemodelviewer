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
    /** Bytes the disk cache holds now (0 when it cannot be read). */
    fun cacheBytes(): Long = try { client.cache?.size() ?: 0L } catch (_: Exception) { 0L }

    /** Empties the disk cache. */
    fun clearCache() { try { client.cache?.evictAll() } catch (_: Exception) { } }

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
            // model data and scans never change, and are kept for 24 hours after they were downloaded (then fetched again if wanted); the disk cache's size is the Settings choice
            // the day cache keeps the finished days (RainDayStore): the midnight 24 h multi-sensor file is read once for that, and not kept here as well
            if (response.isSuccessful && "noaa-mrms-pds" in url && "MultiSensor_QPE_24H_Pass2" in url && url.endsWith("-000000.grib2.gz")) {
                return@Interceptor response.newBuilder().removeHeader("Pragma").header("Cache-Control", "no-store").build()
            }
            if (response.isSuccessful && (".zarr/" in url && !url.endsWith("/.zgroup") || "noaa-mrms-pds" in url && url.endsWith(".grib2.gz") || url.endsWith(".tif"))) {
                response.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=86400").build()
            } else response
        })
        .build().also { it.dispatcher.maxRequestsPerHost = 4 }

    /** One request, listed in [DownloadMonitor] while it runs: the body is read in pieces so the list can show its progress. */
    private fun call(url: String, range: String?): HttpResult {
        val builder = Request.Builder().url(url)
        if (range != null) builder.header("Range", range)
        val id = DownloadMonitor.start(url, range)
        try {
            client.newCall(builder.build()).execute().use { response ->
                val cached = response.networkResponse == null && response.cacheResponse != null
                val body = response.body
                val total = body?.contentLength() ?: -1L
                DownloadMonitor.update(id) { it.copy(state = DownloadEntry.State.Downloading, total = total, status = response.code) }
                val bytes = if (body == null) ByteArray(0) else {
                    val out = java.io.ByteArrayOutputStream(if (total in 1..Int.MAX_VALUE.toLong()) total.toInt() else 64 * 1024)
                    val buffer = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    var received = 0L
                    var shown = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        received += n
                        val t = System.currentTimeMillis()
                        if (t - shown > 250) { shown = t; val r = received; DownloadMonitor.update(id) { it.copy(received = r) } }
                    }
                    out.toByteArray()
                }
                val ok = response.code in 200..299 || response.code == 304
                DownloadMonitor.update(id) {
                    it.copy(received = bytes.size.toLong(), total = if (it.total < 0) bytes.size.toLong() else it.total, finished = System.currentTimeMillis(),
                        state = if (!ok) DownloadEntry.State.Failed else if (cached) DownloadEntry.State.Cached else DownloadEntry.State.Done)
                }
                return HttpResult(response.code, bytes, response.headers.getDate("Last-Modified")?.time?.div(1000))
            }
        } catch (e: IOException) {
            DownloadMonitor.update(id) { it.copy(state = DownloadEntry.State.Failed, finished = System.currentTimeMillis()) }
            throw e
        }
    }

    override suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        try { call(url, null) } catch (e: IOException) { HttpResult(0, ByteArray(0)) }
    }

    override suspend fun getRange(url: String, first: Long, lastInclusive: Long): HttpResult = withContext(Dispatchers.IO) {
        try { call(url, "bytes=$first-$lastInclusive") } catch (e: IOException) { HttpResult(0, ByteArray(0)) }
    }

    companion object {
        // the contact address is set by the user in Settings and appended to this per request (see simplemodelview.userAgent)
        const val USER_AGENT = "simplemodelview/0.1 (unofficial viewer; +https://github.com/intelati26/simplemodelviewer;)"
    }
}
