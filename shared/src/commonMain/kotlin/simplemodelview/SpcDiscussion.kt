package simplemodelview

/** One part of an SPC outlook discussion: a heading ("Summary", "20z Update", "Synopsis and Discussion") and its paragraphs. */
class DiscussionSection(val title: String, val paragraphs: List<String>)

/**
 * An SPC convective outlook's text product, read for the screen: the title, when it was issued and what it is valid for, the headline (the capitalized sentence that opens it:
 * "THERE IS A SLIGHT RISK OF SEVERE THUNDERSTORMS ACROSS ..."), its sections, and the forecaster's initials line.
 */
class SpcDiscussion(
    val title: String,
    val issued: String?,
    val valid: String?,
    val headline: String?,
    val sections: List<DiscussionSection>,
    val forecaster: String?,
)

object SpcDiscussionText {
    private val plain = setOf(
        "there", "is", "are", "a", "an", "the", "of", "and", "or", "in", "into", "over", "across", "from", "to", "near", "through", "risk", "severe", "thunderstorms", "thunderstorm",
        "marginal", "slight", "enhanced", "moderate", "high", "parts", "portions", "areas", "area", "tornadoes", "tornado", "damaging", "winds", "wind", "hail", "expected", "possible",
        "likely", "with", "for", "no", "forecast", "general", "isolated", "scattered", "mainly", "primarily", "along", "between", "mostly",
    )

    /** SPC writes the headline in capitals: sentence case, with the words that are not ordinary ones (the places) capitalized. */
    fun headlineCase(shouted: String): String =
        shouted.lowercase().split(' ').mapIndexed { i, w -> if (i == 0 || w.trim(',', '.') !in plain) w.replaceFirstChar { it.uppercase() } else w }.joinToString(" ")

    /** The product (AFOS) name of the outlook for a day: SWODY1 to SWODY3, then SWOD48 for days 4 to 8. */
    fun pil(day: Int) = when (day) { 1 -> "SWODY1"; 2 -> "SWODY2"; 3 -> "SWODY3"; else -> "SWOD48" }

    /** The latest product's plain text, from the Iowa Environmental Mesonet's archive of NWS text products (the same host that holds the outlook archive). */
    fun url(day: Int) = "https://mesonet.agron.iastate.edu/cgi-bin/afos/retrieve.py?pil=${pil(day)}&limit=1&fmt=text"

    private val validLine = Regex("""^Valid \d{6}Z - \d{6}Z""")

    /** Reads the product text; null when it is not an outlook (empty, an error page). The hard line breaks of the product become paragraphs. */
    fun parse(raw: String): SpcDiscussion? {
        val lines = raw.replace("\r", "").lines().map { it.trimEnd() }
        val validAt = lines.indexOfFirst { validLine.containsMatchIn(it) }
        if (validAt < 0) return null
        val title = lines.take(validAt).lastOrNull { it.contains("Outlook", ignoreCase = true) }?.trim() ?: "Convective outlook"
        val issued = lines.take(validAt).lastOrNull { it.isNotBlank() }?.trim()?.takeIf { it != title && !it.startsWith("NWS ") }
        val valid = lines[validAt].trim()
        var headline: String? = null
        var forecaster: String? = null
        val sections = ArrayList<DiscussionSection>()
        var heading: String? = null
        var paragraphs = ArrayList<String>()
        fun close() {
            if (paragraphs.isNotEmpty() || heading != null) sections += DiscussionSection(heading ?: "Discussion", paragraphs.toList())
            paragraphs = ArrayList(); heading = null
        }
        val blocks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in lines.drop(validAt + 1)) {
            if (line.isBlank()) { if (current.isNotEmpty()) { blocks += current; current = ArrayList() } } else current += line.trim()
        }
        if (current.isNotEmpty()) blocks += current
        fun nice(inner: String) = if (inner.all { c -> !c.isLetter() || c.isUpperCase() }) inner.lowercase().replaceFirstChar { c -> c.uppercase() } else inner
        for (block in blocks) {
            val text = block.joinToString(" ")
            val first = block.first()
            when {
                text == "$$" -> break
                text == "&&" -> {}
                // a heading on its own line ("...SUMMARY...") with its text on the lines under it
                first.startsWith("...") && first.endsWith("...") && first.length > 6 -> {
                    val inner = first.removePrefix("...").removeSuffix("...").trim()
                    val letters = inner.filter { it.isLetter() }
                    val shouting = block.size == 1 && letters.isNotEmpty() && letters.all { it.isUpperCase() } && inner.split(' ').size > 3
                    if (headline == null && sections.isEmpty() && heading == null && paragraphs.isEmpty() && shouting) headline = headlineCase(inner)
                    else { close(); heading = nice(inner); if (block.size > 1) paragraphs += block.drop(1).joinToString(" ") }
                }
                // the headline sentence, broken over lines: "...THERE IS A SLIGHT RISK OF ... ACROSS" / "... PARTS OF X..."
                text.startsWith("...") && text.endsWith("...") && headline == null && sections.isEmpty() -> headline = headlineCase(text.removePrefix("...").removeSuffix("...").trim())
                text.startsWith("..") && !text.startsWith("...") && block.size == 1 -> forecaster = text
                else -> paragraphs += text
            }
        }
        close()
        return SpcDiscussion(title, issued, valid, headline, sections, forecaster)
    }
}
