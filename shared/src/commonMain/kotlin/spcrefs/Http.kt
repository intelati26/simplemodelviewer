package spcrefs

/** HTTP status and body. [status] 0 means no response (network error). */
class HttpResult(val status: Int, val body: ByteArray)

/** The platform's HTTP client (OkHttp on Android). Implementations should cache and send a descriptive User-Agent. */
interface Http {
    suspend fun get(url: String): HttpResult
}

suspend fun Http.exists(url: String): Boolean = get(url).status == 200

/** A deliberately simple check: something@something.tld, no spaces. */
fun isValidEmail(text: String): Boolean {
    val t = text.trim()
    val at = t.indexOf('@')
    if (at < 1 || t.length > 100 || t.any { it.isWhitespace() || it.code > 0x7E } || t.indexOf('@', at + 1) >= 0) return false
    val domain = t.substring(at + 1)
    val dot = domain.lastIndexOf('.')
    return dot > 0 && dot < domain.length - 1
}

/**
 * The User-Agent the app sends: it names the app and project and, when the user has set one, a contact address so a data provider
 * can reach the person if the app ever causes trouble. Headers must be printable ASCII, so anything else is dropped.
 */
fun userAgent(base: String, contact: String): String {
    val clean = contact.filter { it.code in 0x21..0x7E && it != ';' && it != '(' && it != ')' }.take(100)
    return if (clean.isEmpty()) base else "$base contact: $clean"
}
