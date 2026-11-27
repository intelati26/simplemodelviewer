package io.github.intelati26.simplemodelview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import simplemodelview.Http
import simplemodelview.HttpResult
import java.util.concurrent.TimeUnit

/**
 * The client for place search: no cache, and only the app's generic name in the User-Agent (never the contact address of Settings, which is for the weather data
 * servers). What is typed in the search box is the only thing that leaves the phone.
 */
class SearchHttp : Http {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", OkHttpHttp.USER_AGENT).build()).execute().use { r ->
                HttpResult(r.code, r.body?.bytes() ?: ByteArray(0))
            }
        } catch (_: java.io.IOException) { HttpResult(0, ByteArray(0)) }
    }
}
