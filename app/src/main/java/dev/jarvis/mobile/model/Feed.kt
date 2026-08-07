package dev.jarvis.mobile.model

import dev.jarvis.mobile.transport.FileChunk

/** Готовое состояние чата после очередного куска транскрипта. */
data class ChatSnapshot(
    val chat: List<ChatItem>,
    /**
     * Номер первой записи окна среди всех прочитанных.
     *
     * Окно едет, и без сквозного номера список опознаёт записи по месту в нём:
     * под пальцем меняется содержимое, а лента прыгает.
     */
    val chatFrom: Int,
    val artifacts: List<Artifact>,
    val commands: List<String>,
    val workers: List<Worker>,
    val model: String,
)

/**
 * Живая лента одной сессии: держит разобранное состояние между кругами.
 *
 * Первая версия хранила весь прочитанный текст и разбирала его целиком каждые
 * три секунды. На телефоне это несостоятельно дважды: транскрипт живой сессии
 * — это не реплики, а полные ответы инструментов (один `Read` большого файла —
 * сотни килобайт), так что за час набегают десятки мегабайт; и полный разбор
 * такого файла раз в три секунды не успевает закончиться до следующего круга.
 *
 * Поэтому здесь копится РАЗОБРАННОЕ, а не текст: счётчики обращений к файлам,
 * список команд и состояние параллельных работ. Каждый круг видит только новые
 * строки. Прочитанный текст не хранится вообще.
 *
 * Смещение и недописанный хвост живут тут же: экран гаснет и зажигается часто,
 * и терять из-за этого 256 КиБ трафика на каждой разблокировке незачем.
 */
class ChatFeed(val sessionId: String, val path: String) {

    var offset = 0L
        private set

    /** Первый заход сделан: дальше читаем с продолжения, а не хвост файла. */
    var started = false
        private set

    private var rest = ""       // недописанная строка ждёт следующего круга
    private var knownSize = 0L

    private val items = ArrayDeque<ChatItem>()
    private var seen = 0
    private val touches = LinkedHashMap<String, Int>()
    private val commands = ArrayDeque<String>()
    private var watch = Activity.Watch()

    /** Хвост файла, с которого начинать: телефону нужен разговор, а не архив. */
    fun startAt(size: Long) {
        offset = (size - TAIL_BYTES).coerceAtLeast(0)
        knownSize = size
    }

    /** Файл переписали (`/clear`, новый rollout) — начинаем ленту заново. */
    private fun reset() {
        items.clear(); touches.clear(); commands.clear()
        seen = 0
        watch = Activity.Watch()
        rest = ""
        offset = 0
        knownSize = 0
        started = false
    }

    /**
     * Принять кусок. Возвращает новое состояние, либо null — если ничего не
     * изменилось и трогать экран незачем.
     *
     * Считать это надо не на главном потоке: разбор JSON и пересборка списков
     * стоят миллисекунды, а вызывается оно каждые три секунды.
     */
    fun accept(chunk: FileChunk): ChatSnapshot? {
        if (chunk.rewound(offset, knownSize)) {
            // Узел на смещении за концом файла отдаёт пустой кусок, так что
            // самих данных тут никогда нет: перечитываем с начала нового файла.
            reset()
            return snapshot()
        }
        var text = chunk.data
        // Первая строка куска почти наверняка обрезана посередине — она из тех
        // байт, что мы решили не читать.
        if (!started && offset > 0) text = text.substringAfter('\n', "")
        offset = chunk.next
        knownSize = chunk.size
        started = true
        if (text.isEmpty()) return null

        val combined = rest + text
        val cut = combined.lastIndexOf('\n')
        if (cut < 0) { rest = combined; return null }
        val whole = combined.substring(0, cut)
        rest = combined.substring(cut + 1)
        if (whole.isBlank()) return null

        val fresh = Transcript.parse(whole)
        for (i in fresh) {
            items.addLast(i)
            seen++
            if (i.kind == Kind.TOOL) fold(i)
        }
        while (items.size > ITEM_CAP) items.removeFirst()
        // Работы пересобираем, только если в новых строках действительно был
        // вызов инструмента или уведомление о задаче — иначе их состояние
        // измениться не могло.
        val touched = watch.feed(whole)
        return snapshot(rebuild = touched)
    }

    /** Артефакты и команды считаем на лету — списком целиком их не пересобрать. */
    private fun fold(i: ChatItem) {
        if (i.text in FILE_TOOLS) {
            val p = i.path.trim()
            if (p.isNotEmpty()) touches[p] = (touches[p] ?: 0) + 1
        }
        if (i.text == "Bash" && i.detail.isNotBlank()) {
            commands.remove(i.detail)   // повтор поднимается наверх, а не двоится
            commands.addFirst(i.detail)
            while (commands.size > COMMAND_CAP) commands.removeLast()
        }
    }

    private var lastWorkers: List<Worker> = emptyList()
    private var lastModel = ""

    private fun snapshot(rebuild: Boolean = true): ChatSnapshot {
        if (rebuild) {
            val r = watch.report()
            lastWorkers = r.workers
            if (r.model.isNotBlank()) lastModel = r.model
        }
        // Копируем только видимый хвост: держим-то мы тысячи записей.
        val n = minOf(CHAT_WINDOW, items.size)
        val tail = ArrayList<ChatItem>(n)
        for (i in items.size - n until items.size) tail.add(items[i])
        return ChatSnapshot(
            chat = tail,
            chatFrom = seen - n,
            artifacts = touches.entries.map { Artifact(it.key, it.value) }.sortedByDescending { it.touches },
            commands = commands.toList(),
            workers = lastWorkers,
            model = lastModel,
        )
    }

    companion object {
        /** Инструменты, у которых `file_path` означает именно правку файла. */
        private val FILE_TOOLS = setOf("Edit", "Write", "NotebookEdit", "MultiEdit", "Read")

        const val TAIL_BYTES = 256L * 1024

        /** Сколько записей держим в памяти. На экран уходит меньше. */
        const val ITEM_CAP = 2_000
        const val CHAT_WINDOW = 200
        const val COMMAND_CAP = 50
    }
}
