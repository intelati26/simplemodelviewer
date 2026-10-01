package spcrefs

/** HTTP status and body. [status] 0 means no response (network error). */
class HttpResult(val status: Int, val body: ByteArray)

/** The platform's HTTP client (OkHttp on Android). Implementations should cache and send a descriptive User-Agent. */
interface Http {
    suspend fun get(url: String): HttpResult
}

suspend fun Http.exists(url: String): Boolean = get(url).status == 200
