package dev.jarvis.mobile.model

import dev.jarvis.mobile.transport.Recorded
import dev.jarvis.mobile.transport.str
import kotlinx.serialization.json.JsonObject

/** Состояние сессии — сознательно грубее, чем у настольного Jarvis. */
enum class Status { IDLE, WORKING, WAITING, DONE, LIMIT }

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
            out[sid] = prev.copy(
                cwd = cwd,
                project = cwd?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null } ?: prev.project,
                agent = env.str("agent") ?: prev.agent,
                pane = env.str("tmux_pane")?.takeIf { it.isNotEmpty() } ?: prev.pane,
                transcript = payload.str("transcript_path") ?: prev.transcript,
                updatedAt = maxOf(rec.at, prev.updatedAt),
                status = statusFor(event, prev.status),
                question = questionFor(event, payload, prev.question),
                detail = detailFor(event, payload, prev.detail),
            )
        }
        return out
    }

    private fun statusFor(event: String, prev: Status): Status = when (event) {
        "prompt", "pre-tool", "post-tool", "session-start" -> Status.WORKING
        // «спрашивает» — единственное состояние, ради которого стоит доставать
        // телефон: агент встал и ждёт человека
        "notification", "permission" -> Status.WAITING
        "stop" -> Status.DONE
        "stop-failure" -> Status.DONE
        else -> prev
    }

    private fun questionFor(event: String, payload: JsonObject, prev: String?): String? = when (event) {
        "notification", "permission" -> payload.str("message") ?: payload.str("prompt") ?: prev ?: "ждёт ответа"
        // ответили — вопроса больше нет
        "prompt", "post-tool", "stop" -> null
        else -> prev
    }

    private fun detailFor(event: String, payload: JsonObject, prev: String): String = when (event) {
        "prompt" -> payload.str("prompt")?.oneLine(120) ?: "работает"
        "pre-tool" -> payload.str("tool_name")?.let { "выполняет $it" } ?: prev
        "stop" -> "закончила"
        "notification", "permission" -> payload.str("message")?.oneLine(120) ?: "спрашивает"
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
    compareByDescending<Session> { it.status == Status.WAITING }.thenByDescending { it.updatedAt }
)

/** Заглушка на случай, если конверт не принёс ничего именуемого. */
fun Session.title(): String = project ?: cwd ?: id.take(8)
