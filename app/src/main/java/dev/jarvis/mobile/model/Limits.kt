package dev.jarvis.mobile.model

/**
 * Лимиты подписки: пятичасовое окно и недельное.
 *
 * Разбор текста `claude /usage` — тот же, что в настольном Jarvis. Формат
 * внутренний и дрейфует, поэтому читаем defensive: не нашли строку — значит её
 * нет, а не «ноль процентов». Ноль и «неизвестно» на экране должны выглядеть
 * по-разному, иначе человек поверит, что лимит свободен.
 */
data class Window(val pct: Int, val resets: String)

data class Limits(
    val session: Window? = null,
    val week: Window? = null,
    /** Недельное окно конкретной модели: «Current week (Sonnet)» и т.п. */
    val model: Pair<String, Window>? = null,
    val at: Long = 0,
) {
    val known: Boolean get() = session != null || week != null

    /** Самое узкое место — по нему и стоит судить, сколько осталось. */
    val worst: Window? get() = listOfNotNull(session, week).maxByOrNull { it.pct }
}

object LimitsParser {

    private val SESSION = Regex(
        """current session:\s*(\d+)%\s*used\s*·\s*resets\s+([^\n(]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val WEEK = Regex(
        """current week \(all models\):\s*(\d+)%\s*used\s*·\s*resets\s+([^\n(]+)""",
        RegexOption.IGNORE_CASE,
    )
    // Модель в скобках — любая: там бывает Sonnet, Opus, Fable… Жёстко зашитое
    // имя означало бы, что строка пропадает при смене модели.
    private val MODEL = Regex(
        """current week \(([^)]+)\):\s*(\d+)%\s*used(?:\s*·\s*resets\s+([^\n(]+))?""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, at: Long = 0): Limits {
        val session = SESSION.find(text)?.let { Window(it.pct(1), it.str(2)) }
        val week = WEEK.find(text)?.let { Window(it.pct(1), it.str(2)) }
        val model = MODEL.findAll(text)
            .map { it.groupValues[1] to it }
            .firstOrNull { !it.first.equals("all models", ignoreCase = true) }
            ?.let { (name, m) -> name to Window(m.pct(2), m.str(3)) }
        return Limits(session, week, model, at)
    }

    private fun MatchResult.pct(group: Int): Int =
        groupValues.getOrNull(group)?.toIntOrNull()?.coerceIn(0, 100) ?: 0

    private fun MatchResult.str(group: Int): String =
        groupValues.getOrNull(group)?.trim()?.trimEnd('·', ' ') ?: ""
}
