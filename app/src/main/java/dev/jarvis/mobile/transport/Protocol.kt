package dev.jarvis.mobile.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Протокол узла `jarvis-node` — ровно тот, что описан в его README.
 *
 * Телефон говорит с узлом напрямую, а не через ноутбук: смысл приложения в том,
 * чтобы видеть агентов, когда ноутбук закрыт. Узел ничего не интерпретирует —
 * он отдаёт сырые конверты хуков, и разбирать их приходится здесь.
 */
@Serializable
data class Hello(
    val version: String = "",
    val host: String = "",
    @SerialName("uptime_ms") val uptimeMs: Long = 0,
    val cursor: Long = 0,
    val buffered: Long = 0,
    val capacity: Long = 0,
)

/** Одно событие ленты узла: конверт хука плюс метки узла. */
@Serializable
data class Recorded(
    val cursor: Long = 0,
    val at: Long = 0,
    val envelope: JsonObject = JsonObject(emptyMap()),
)

/**
 * Страница `/events`. `gap` — узел честно признал, что часть событий вытеснена
 * из кольцевого буфера: делать вид, что ничего не потерялось, нельзя.
 */
@Serializable
data class EventsPage(
    val cursor: Long = 0,
    val gap: Boolean = false,
    val events: List<Recorded> = emptyList(),
)

/** Кусок транскрипта `/file`. */
@Serializable
data class FileChunk(
    val path: String = "",
    val from: Long = 0,
    val next: Long = 0,
    val size: Long = 0,
    val eof: Boolean = false,
    val data: String = "",
)

@Serializable
data class Pane(
    val pane: String = "",
    val session: String = "",
    val pid: Long = 0,
    val cwd: String = "",
)

@Serializable
data class PanesReply(
    val panes: List<Pane> = emptyList(),
    val error: String = "",
)

@Serializable
data class RemoteSession(
    val id: String = "",
    val at: Long = 0,
    val size: Long = 0,
    val path: String = "",
)

@Serializable
data class RemoteProject(
    val dir: String = "",
    val cwd: String? = null,
    val agent: String = "claude",
    val lastAt: Long = 0,
    val count: Long = 0,
    val sessions: List<RemoteSession> = emptyList(),
)

@Serializable
data class ProjectsReply(val projects: List<RemoteProject> = emptyList())

/** Шаг плана ответа на вопрос агента: именованная клавиша либо вставка текста. */
@Serializable
data class KeyStep(val key: String? = null, val text: String? = null)

/** Строковое поле конверта или null — конверты приходят от чужого кода. */
internal fun JsonObject.str(field: String): String? =
    (this[field] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/** Числовое поле конверта или null. */
internal fun JsonObject.num(field: String): Long? =
    (this[field] as? JsonPrimitive)?.content?.toLongOrNull()
