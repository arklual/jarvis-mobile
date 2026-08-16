package dev.jarvis.mobile.model

import dev.jarvis.mobile.transport.Recorded
import dev.jarvis.mobile.transport.str
import kotlinx.serialization.json.JsonObject

/** Состояние сессии — сознательно грубее, чем у настольного Jarvis. */
enum class Status {
    IDLE,
    WORKING,
    WAITING,
    DONE,

    /** Упёрлись в лимит аккаунта: работа не продолжится до сброса окна. */
    LIMIT,

    /** Ход оборвался ошибкой: перегрузка API, сеть, биллинг. Никто не продолжит. */
    FAILED,
}

/**
 * Чем закончился `stop-failure`.
 *
 * Правила те же, что в настольном Jarvis (`limits::classify_failure`): хук
 * общий, значит и разбор должен быть общим — иначе телефон и ноутбук скажут о
 * происходящем разное. Умолчание — «сорвался», а НЕ лимит: перегрузка API и
 * обрыв сети случаются в разы чаще, и объявлять их упиранием в стену значит
 * пугать человека там, где надо просто повторить.
 */
enum class Failure { LIMIT, BILLING, OVERLOADED, TRANSIENT }

data class Session(
    val id: String,
    val status: Status = Status.IDLE,
    val project: String? = null,
    val cwd: String? = null,
    val agent: String = "claude",
    val pane: String? = null,
    val transcript: String? = null,
    val detail: String = "",
    val question: String? = null,
    val updatedAt: Long = 0,
)

/**
 * Свёртка сырых конвертов хуков в список сессий.
 *
 * Это НЕ порт редьюсера из настольного Jarvis и не должен им становиться. Там
 * статусы, ходы, сводки и доска задач — здесь ровно то, ради чего лезут в
 * телефон: кто работает, кто спрашивает, кто закончил. Правило простое —
 * состояние задаёт последнее событие сессии.
 *
 * Осознанное упрощение: расходясь в мелочах с настольной версией, мобильная
 * остаётся понятной. Попытка повторить её один в один дала бы второй источник
 * правды, который неизбежно разъедется с первым.
 */
object Reducer {
    fun apply(current: Map<String, Session>, batch: List<Recorded>): Map<String, Session> {
        val out = current.toMutableMap()
        for (rec in batch) {
            val env = rec.envelope
            val payload = env["payload"] as? JsonObject ?: JsonObject(emptyMap())
            val sid = payload.str("session_id")?.takeIf { it.isNotEmpty() } ?: continue
            val event = env.str("event").orEmpty()

            if (event == "session-end") {
                out.remove(sid)
                continue
            }
            val prev = out[sid] ?: Session(id = sid)
            val cwd = payload.str("cwd") ?: prev.cwd
            // Уведомление уведомлению рознь: тем же хуком приходит и вопрос, и
            // «агент закончил, твой ход». Классифицируем один раз здесь, иначе
            // ниже пришлось бы повторять это в трёх местах.
            val asks = event.isQuestion(payload)
            out[sid] = prev.copy(
                cwd = cwd,
                project = cwd?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null } ?: prev.project,
                agent = env.str("agent") ?: prev.agent,
                pane = env.str("tmux_pane")?.takeIf { it.isNotEmpty() } ?: prev.pane,
                transcript = payload.str("transcript_path") ?: prev.transcript,
                updatedAt = maxOf(rec.at, prev.updatedAt),
                status = statusFor(event, asks, prev.status, payload),
                question = questionFor(event, asks, payload, prev.question),
                detail = detailFor(event, asks, payload, prev.detail),
            )
        }
        return out
    }

    /**
     * Правда ли агент о чём-то спрашивает.
     *
     * Хук `notification` у Claude Code — не синоним вопроса: тем же событием
     * приходит «Claude is waiting for your input», то есть «я закончил, твой
     * ход». Показывать на него карточку с вариантами ответа неоткуда: вариантов
     * нет, и человек видит виджет выбора там, где выбирать нечего.
     *
     * Судим по `notification_type`, если он есть, — это прямой ответ агента на
     * вопрос «чего я хочу». Нет его (старые версии) — по тексту.
     */
    private fun String.isQuestion(payload: JsonObject): Boolean {
        if (this == "permission") return true
        if (this != "notification") return false
        payload.str("notification_type")?.let { type ->
            return type == "permission_prompt" || type == "elicitation_dialog"
        }
        val msg = payload.str("message").orEmpty()
        return msg.isNotBlank() && !IDLE.containsMatchIn(msg)
    }

    /** «Ждёт твоего ввода» — это конец работы, а не вопрос. */
    private val IDLE = Regex("waiting for your input", RegexOption.IGNORE_CASE)

    private val BILLING = Regex("billing|payment|insufficient|credit", RegexOption.IGNORE_CASE)
    private val RATE = Regex("rate.?limit|usage limit|quota|429|limit reached|limit_exceeded", RegexOption.IGNORE_CASE)
    private val OVERLOAD = Regex("overload|503|529|capacity", RegexOption.IGNORE_CASE)

    /**
     * Служебные поля: смотреть в них — значит гадать по путям и идентификаторам.
     *
     * Первая версия искала по всей полезной нагрузке разом, а там всегда лежат
     * `session_id`, `cwd` и путь транскрипта. Идентификатор сессии
     * шестнадцатеричный, так что «429» в нём встречается запросто — и любой
     * сорвавшийся ход такой сессии объявлялся упиранием в лимит. Проект в
     * каталоге `credit-scoring` давал «ошибку биллинга» тем же способом.
     */
    private val STRUCTURAL = setOf(
        "session_id", "cwd", "transcript_path", "tmux_pane", "pane",
        "hook_event_name", "notification_type", "permission_mode", "tool_name",
    )

    /** Собрать из полезной нагрузки только то, что может быть текстом ошибки. */
    private fun errorText(payload: JsonObject): String =
        payload.entries
            .filter { it.key !in STRUCTURAL }
            .joinToString(" ") { (_, v) -> v.toString() }

    /** Что именно случилось на `stop-failure` — по тексту ошибки, а не по путям. */
    fun classify(payload: JsonObject): Failure {
        val raw = errorText(payload)
        return when {
            BILLING.containsMatchIn(raw) -> Failure.BILLING
            RATE.containsMatchIn(raw) -> Failure.LIMIT
            OVERLOAD.containsMatchIn(raw) -> Failure.OVERLOADED
            else -> Failure.TRANSIENT
        }
    }

    private fun statusFor(event: String, asks: Boolean, prev: Status, payload: JsonObject): Status = when {
        event == "prompt" || event == "pre-tool" || event == "post-tool" || event == "session-start" ->
            Status.WORKING
        // «спрашивает» — единственное состояние, ради которого стоит доставать
        // телефон: агент встал и ждёт человека
        asks -> Status.WAITING
        // уведомление без вопроса — это «закончил, твой ход»
        event == "notification" -> Status.DONE
        // Сорванный ход — НЕ «закончил». Раньше он сворачивался в «закончила»,
        // и человек, увидев это на телефоне, откладывал его в полной
        // уверенности, что работа сделана. На деле она стояла.
        event == "stop-failure" ->
            if (classify(payload) == Failure.LIMIT) Status.LIMIT else Status.FAILED
        event == "stop" -> Status.DONE
        else -> prev
    }

    private fun questionFor(event: String, asks: Boolean, payload: JsonObject, prev: String?): String? = when {
        asks -> payload.str("message") ?: payload.str("prompt") ?: prev ?: "ждёт ответа"
        // ответили либо закончили — вопроса больше нет
        event == "prompt" || event == "post-tool" || event == "stop" || event == "notification" -> null
        else -> prev
    }

    private fun detailFor(event: String, asks: Boolean, payload: JsonObject, prev: String): String = when {
        asks -> payload.str("message")?.oneLine(120) ?: "спрашивает"
        event == "stop-failure" -> when (classify(payload)) {
            Failure.LIMIT -> "лимит использования — до сброса окна не продолжится"
            Failure.BILLING -> "ошибка биллинга"
            Failure.OVERLOADED -> "API перегружен — можно повторить"
            Failure.TRANSIENT -> "ход прервался ошибкой"
        }
        event == "prompt" -> payload.str("prompt")?.oneLine(120) ?: "работает"
        event == "pre-tool" -> payload.str("tool_name")?.let { "выполняет $it" } ?: prev
        event == "stop" -> "закончила"
        event == "notification" -> "закончила · ждёт твоего ответа"
        else -> prev
    }
}

/** Однострочная и обрезанная версия текста — в список многострочное не влезает. */
fun String.oneLine(limit: Int): String {
    val flat = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= limit) flat else flat.take(limit - 1) + "…"
}

/** Свежие сверху, спрашивающие — выше всех: за ними и лезут в телефон. */
fun Collection<Session>.sortedForList(): List<Session> = sortedWith(
    // Наверх — всё, что само не поедет: сначала вопрос (ответ нужен сейчас),
    // следом вставшие. Работающие едут сами, их место ниже.
    compareByDescending<Session> { it.status == Status.WAITING }
        .thenByDescending { it.status == Status.LIMIT || it.status == Status.FAILED }
        .thenByDescending { it.updatedAt }
)

/**
 * Выбросить сессии, чьей паны на машине уже нет.
 *
 * Нужно тем, кто держит сессии, о которых узел больше не помнит событий:
 * кольцо у него не бесконечное, а сессия, которая молча ждёт ответа, из него
 * вытесняется и при этом жива. Держать такую в списке правильно, но кто-то
 * должен вычёркивать закончившиеся — пана и есть этот признак.
 *
 * Сессию без паны не трогаем: запущена вне tmux, судить не по чему. И пустой
 * список пан ничего не вычёркивает — это «tmux не поднят», а не «всё умерло».
 */
fun Map<String, Session>.dropDeadPanes(live: Set<String>): Map<String, Session> {
    if (live.isEmpty()) return this
    return filterValues { s -> s.pane == null || s.pane in live }
}

/** Заглушка на случай, если конверт не принёс ничего именуемого. */
fun Session.title(): String = project ?: cwd ?: id.take(8)

/** Во что складывается список сессий: сколько ждёт, сколько работает, сколько кончило. */
data class Tally(
    val waiting: Int = 0,
    val working: Int = 0,
    val done: Int = 0,
    /** Встали и сами не поедут: упёрлись в лимит либо сорвались. */
    val stuck: Int = 0,
) {
    val any: Boolean get() = waiting > 0 || working > 0 || done > 0 || stuck > 0
}

/**
 * Свести сессии к трём числам.
 *
 * Категория у сессии РОВНО одна. Считать их независимыми условиями нельзя:
 * вопрос снимается только ответом через приложение, а если человек ответил в
 * терминале на самой машине, `question` останется висеть — и сессия попала бы
 * разом и в «ждёт», и в «в работе». Человек пошёл бы искать вопрос, которого
 * уже нет.
 */
fun Collection<Session>.tally(): Tally {
    var waiting = 0
    var working = 0
    var done = 0
    var stuck = 0
    for (s in this) when {
        s.status == Status.WAITING || s.question != null -> waiting++
        s.status == Status.LIMIT || s.status == Status.FAILED -> stuck++
        s.status == Status.WORKING -> working++
        s.status == Status.DONE -> done++
    }
    return Tally(waiting, working, done, stuck)
}
