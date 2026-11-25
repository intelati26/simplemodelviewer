package simplemodelview

/** The plain-language side of the Today screen: SPC's category as a number and a sentence, and the hazard chances at a place. */
object TodayText {
    /** 0 general thunder or none, 1 Marginal ... 5 High: SPC's five levels. */
    fun level(title: String?): Int = when {
        title == null -> 0
        title.contains("Marginal", true) -> 1
        title.contains("Slight", true) -> 2
        title.contains("Enhanced", true) -> 3
        title.contains("Moderate", true) -> 4
        title.contains("High", true) -> 5
        else -> 0
    }

    fun levelName(level: Int) = when (level) { 1 -> "Marginal"; 2 -> "Slight"; 3 -> "Enhanced"; 4 -> "Moderate"; 5 -> "High"; else -> "No" }

    /** "Slight risk (2 of 5) of severe storms" / "No severe risk here". */
    fun sentence(level: Int, whenText: String = ""): String =
        if (level <= 0) "No severe risk here" + whenText else "${levelName(level)} risk ($level of 5) of severe storms$whenText"

    /** "2% Tornado Risk" -> 2; null when the title carries no percent. */
    fun percent(title: String?): Int? = title?.let { Regex("""(\d+)\s*%""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** The hazard with the highest chance (a tie goes to tornado, then wind, then hail) and its percent; null when every chance is zero or unknown. */
    fun topThreat(tornado: Int?, wind: Int?, hail: Int?): Pair<String, Int>? =
        listOf("Tornado" to tornado, "Wind" to wind, "Hail" to hail).filter { (it.second ?: 0) > 0 }.maxByOrNull { it.second ?: 0 }?.let { it.first to (it.second ?: 0) }

    /** "Hail is the top threat here (15%)". */
    fun topThreatLine(top: Pair<String, Int>?): String? = top?.let { (name, p) -> (if (name == "Wind") "Damaging wind" else name) + " is the top threat here ($p%)" }

    /** The day of the outlook for the saved-place card: "today", "tomorrow", "Friday"... [dayOfWeek] is 0 = Sunday for the day the outlook covers. */
    fun dayName(day: Int, dayOfWeek: Int): String = when (day) {
        1 -> "today"; 2 -> "tomorrow"
        else -> listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")[((dayOfWeek % 7) + 7) % 7]
    }
}
