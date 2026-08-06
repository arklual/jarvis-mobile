package dev.jarvis.mobile.model

/**
 * Артефакты работы: что агент трогал и что запускал.
 *
 * Собираем из тех же вызовов инструментов, что уже разобраны для ленты, — это
 * единственный источник, который есть у телефона. Каталог проекта агент знает,
 * а мы нет, поэтому путь храним как пришёл: узел отдаёт файлы из каталогов
 * живых пан, и абсолютный путь ему как раз и нужен.
 */
data class Artifact(
    val path: String,
    /** Сколько раз агент к нему обращался — по этому и сортируем. */
    val touches: Int,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val dir: String get() = path.trimEnd('/').substringBeforeLast('/', "")
}

object Artifacts {
    /** Инструменты, у которых `file_path` означает именно правку файла. */
    private val FILE_TOOLS = setOf("Edit", "Write", "NotebookEdit", "MultiEdit", "Read")

    /**
     * Файлы из ленты, свежие и частые сверху.
     *
     * Считаем обращения, а не различаем чтение и запись: телефону важно «над
     * чем шла работа», а различать их по имени инструмента — гадание, которое
     * всё равно развалится на MCP-инструментах.
     */
    fun from(items: List<ChatItem>): List<Artifact> {
        val counts = LinkedHashMap<String, Int>()
        for (i in items) {
            if (i.kind != Kind.TOOL) continue
            if (i.text !in FILE_TOOLS) continue
            // Именно path, а не detail: в ленте лежит короткое имя, а узлу
            // для открытия файла нужен путь целиком.
            val path = i.path.trim()
            if (path.isEmpty()) continue
            counts[path] = (counts[path] ?: 0) + 1
        }
        return counts.entries
            .map { Artifact(it.key, it.value) }
            .sortedByDescending { it.touches }
    }

    /** Команды, которые агент запускал, — свежие сверху, без повторов подряд. */
    fun commands(items: List<ChatItem>): List<String> =
        items.asReversed()
            .filter { it.kind == Kind.TOOL && it.text == "Bash" && it.detail.isNotBlank() }
            .map { it.detail }
            .distinct()
            .take(50)
}
