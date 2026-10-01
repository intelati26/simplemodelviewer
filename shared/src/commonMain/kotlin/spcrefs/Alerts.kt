package spcrefs

/** "Tell me when this product reaches this value at this place" (threshold in the product's shown units). */
data class AlertRule(val placeName: String, val lat: Double, val lon: Double, val productId: String, val productTitle: String, val threshold: Double) {
    val key get() = "$placeName|$productId"
}

fun encodeRules(rules: List<AlertRule>): String = rules.joinToString("\n") {
    listOf(it.placeName, it.lat, it.lon, it.productId, it.productTitle, it.threshold).joinToString("|") { f -> f.toString().replace('|', '/').replace('\n', ' ') }
}

fun decodeRules(text: String): List<AlertRule> = text.lines().mapNotNull { line ->
    val p = line.split('|')
    if (p.size != 6) return@mapNotNull null
    AlertRule(p[0], p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toDoubleOrNull() ?: return@mapNotNull null, p[3], p[4], p[5].toDoubleOrNull() ?: return@mapNotNull null)
}

/** The first time the series reaches the rule's threshold, with the peak. */
data class AlertHit(val firstIndex: Int, val firstTime: Long, val peak: Double, val peakTime: Long)

fun AlertRule.evaluate(series: PointSeries): AlertHit? {
    val first = series.values.indexOfFirst { !it.isNaN() && it >= threshold }
    if (first < 0) return null
    val peak = series.peakIndex ?: return null
    return AlertHit(first, series.times[first], series.values[peak], series.times[peak])
}

/** Text of the notification; always carries the experimental-guidance wording. */
fun AlertRule.message(hit: AlertHit, series: PointSeries, run: Cycle): String =
    "$placeName: $productTitle reaches ${fixed(hit.peak, series.digits)}${if (series.units.isEmpty()) "" else " " + series.units} " +
        "(alert level ${fixed(threshold, series.digits)}), first ${formatUtc(hit.firstTime)}. Run ${run.label}. Experimental guidance, not a warning."
