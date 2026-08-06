package dev.jarvis.mobile.model

/**
 * Варианты ответа, снятые с ЭКРАНА агента.
 *
 * Раньше телефон рисовал кнопки 1–4 всегда: сколько вариантов на самом деле —
 * он не знал. В хуке их нет, и выдумывать их количество значит показывать
 * кнопки, за которыми ничего не стоит. Настоящий список есть ровно в одном
 * месте — на экране паны, и оттуда мы его и берём.
 */
data class Picker(val options: List<Option>, val confirm: Boolean) {
    data class Option(val number: Int, val label: String, val selected: Boolean)

    val empty: Boolean get() = options.isEmpty()
}

object PickerReader {

    /** `❯ 1. Yes, I trust this folder` — маркер выбора, номер, точка, текст. */
    private val OPTION = Regex("""^\s*([>❯▶]?)\s*(\d+)[.)]\s+(.+?)\s*$""")

    /** Строка-подсказка внизу пикера: по ней видно, что это вообще пикер. */
    private val CONFIRM = Regex("""enter to (confirm|select)""", RegexOption.IGNORE_CASE)

    /**
     * Разобрать экран паны. Смотрим ХВОСТ: пикер всегда внизу, а выше может
     * лежать вывод команд с точно такой же нумерацией — списком файлов,
     * например. Ошибиться тут значит предложить нажать цифру, которая уедет в
     * диалог текстом.
     */
    fun read(screen: String, tail: Int = 24): Picker {
        val lines = screen.lines().takeLast(tail)
        val confirm = lines.any { CONFIRM.containsMatchIn(it) }
        val options = lines.mapNotNull { line ->
            OPTION.find(line)?.let { m ->
                val (marker, number, label) = m.destructured
                number.toIntOrNull()?.let { n ->
                    Picker.Option(n, label.trim(), selected = marker.isNotEmpty())
                }
            }
        }
        // Нумерация обязана быть непрерывной с единицы: случайные «1.» и «7.»
        // из вывода команды пикером не являются.
        val ordered = options.sortedBy { it.number }
        val looksLikeList = ordered.isNotEmpty() &&
            ordered.mapIndexed { i, o -> o.number == i + 1 }.all { it }
        return if (looksLikeList) Picker(ordered, confirm) else Picker(emptyList(), confirm)
    }
}
