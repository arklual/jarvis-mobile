package dev.jarvis.mobile.model

/**
 * Чем и как агент сейчас думает.
 *
 * Два разных источника, потому что другого выбора нет. Модель пишется в каждую
 * запись ассистента — её видно из транскрипта. А effort наружу не отдаётся
 * нигде: настольный Jarvis ведёт его оптимистично, помня, что выставил сам, но
 * телефон мог и не выставлять. Единственное место, где он написан честно, —
 * подвал самого агента, и мы читаем оттуда.
 */
data class Vitals(val model: String = "", val effort: String = "") {
    val known: Boolean get() = model.isNotBlank() || effort.isNotBlank()
}

object VitalsReader {

    /** `● high · /effort` в подвале TUI. */
    private val EFFORT = Regex("""([a-zA-Zа-яА-Я]+)\s*·\s*/effort""")

    /** Иногда подвал пишет и модель: `Opus 5 · /model`. */
    private val MODEL_LINE = Regex("""([\w.\- ]+?)\s*·\s*/model""")

    /**
     * Из экрана паны. Смотрим хвост: подвал всегда внизу, а выше те же слова
     * могут встретиться в выводе команды.
     */
    fun fromScreen(screen: String): Vitals {
        val tail = screen.lines().takeLast(6).joinToString("\n")
        return Vitals(
            model = MODEL_LINE.find(tail)?.groupValues?.get(1)?.trim().orEmpty(),
            effort = EFFORT.find(tail)?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }

    /**
     * Человеческое имя модели из идентификатора: `claude-opus-5` → `Opus`.
     *
     * Показывать сырой идентификатор незачем — он длинный и в строку не влезает,
     * а различать модели по нему человеку не нужно.
     */
    fun friendly(id: String): String {
        val s = id.lowercase()
        return when {
            "fable" in s -> "Fable"
            "opus" in s -> "Opus"
            "sonnet" in s -> "Sonnet"
            "haiku" in s -> "Haiku"
            "codex" in s -> "Codex"
            s.startsWith("gpt") -> id
            id.isBlank() -> ""
            else -> id
        }
    }
}
