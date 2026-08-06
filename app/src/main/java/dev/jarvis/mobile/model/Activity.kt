package dev.jarvis.mobile.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Что сейчас происходит в сессии, кроме самого разговора. */
enum class Kind2 { SUBAGENT, SHELL, WORKFLOW }

enum class RunState { RUNNING, DONE, FAILED }

/**
 * Одна параллельная работа: субагент, фоновая команда или воркфлоу.
 *
 * Агент почти никогда не работает в одиночку: он поднимает субагентов, запускает
 * долгие команды в фоне и ждёт их. В ленте всё это выглядит одинаковыми чипами
 * «Agent» и «Bash», и понять, кто чем занят и кто ещё жив, нельзя. Отсюда
 * отдельный список.
 */
data class Worker(
    val kind: Kind2,
    /** Идентификатор задачи (`a…`/`b…`) либо, пока его нет, id вызова. */
    val id: String,
    val title: String,
    val subtitle: String = "",
    val model: String = "",
    val state: RunState = RunState.RUNNING,
    /** Итог: отчёт субагента или вывод команды, когда он пришёл. */
    val report: String = "",
)

/** Что удалось узнать из транскрипта: параллельные работы и текущая модель. */
data class Report(val workers: List<Worker>, val model: String)

object Activity {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** «agentId: a315eca60e487baad» в ответе на запуск субагента. */
    private val AGENT_ID = Regex("""agentId:\s*(\S+)""")

    /** «Command running in background with ID: bk4f1gl2t». */
    private val SHELL_ID = Regex("""background with ID:\s*(\S+)""")

    /** Уведомление о завершении: id и статус приезжают вместе. */
    private val TASK_DONE = Regex("""<task-id>(\S+?)</task-id>.*?<status>(\w+)</status>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Разобрать транскрипт в список параллельных работ.
     *
     * Один проход: собираем вызовы, их результаты и уведомления о завершении, а
     * потом сшиваем. Порядок в файле не гарантирован (уведомление может прийти
     * задолго после запуска), поэтому сшивать приходится по идентификаторам, а
     * не по соседству строк.
     */
    fun parse(text: String): Report {
        val calls = LinkedHashMap<String, Pair<String, JsonObject>>() // toolId → (имя, вход)
        val results = HashMap<String, String>()                       // toolId → текст ответа
        val finished = HashMap<String, String>()                      // taskId → статус
        var model = ""

        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            val message = obj["message"] as? JsonObject ?: continue
            (message["model"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() && !it.startsWith("<") }
                ?.let { model = it }
            val content = message["content"] as? JsonArray ?: continue
            for (raw in content) {
                val b = raw as? JsonObject ?: continue
                when (b.str("type")) {
                    "tool_use" -> {
                        val id = b.str("id") ?: continue
                        val name = b.str("name") ?: continue
                        calls[id] = name to ((b["input"] as? JsonObject) ?: JsonObject(emptyMap()))
                    }
                    "tool_result" -> {
                        val id = b.str("tool_use_id") ?: continue
                        results[id] = b.contentText()
                    }
                    "text" -> {
                        // Уведомления о завершении приезжают обычным текстом —
                        // другого признака «работа закончилась» в файле нет.
                        val t = b.str("text").orEmpty()
                        if ("task-id" in t) {
                            TASK_DONE.findAll(t).forEach { finished[it.groupValues[1]] = it.groupValues[2] }
                        }
                    }
                }
            }
        }

        val out = mutableListOf<Worker>()
        for ((toolId, call) in calls) {
            val (name, input) = call
            val result = results[toolId].orEmpty()
            when {
                name == "Agent" || name == "Task" -> {
                    val id = AGENT_ID.find(result)?.groupValues?.get(1) ?: toolId
                    out.add(
                        Worker(
                            kind = Kind2.SUBAGENT,
                            id = id,
                            title = input.str("description") ?: input.str("subagent_type") ?: "субагент",
                            subtitle = input.str("subagent_type").orEmpty(),
                            model = input.str("model").orEmpty().ifBlank { model },
                            state = state(id, finished, result),
                            report = if (finished[id] != null) result else "",
                        )
                    )
                }
                name == "Bash" && input.bool("run_in_background") -> {
                    val id = SHELL_ID.find(result)?.groupValues?.get(1) ?: toolId
                    out.add(
                        Worker(
                            kind = Kind2.SHELL,
                            id = id,
                            title = input.str("description") ?: "фоновая команда",
                            subtitle = input.str("command").orEmpty().oneLine(160),
                            state = state(id, finished, result),
                        )
                    )
                }
                name == "Workflow" -> {
                    // Скрипт воркфлоу — это килобайты кода. В ленте он и делает
                    // «криво»: полезны имя, описание и фазы, а не текст.
                    val meta = metaOf(input.str("script").orEmpty())
                    out.add(
                        Worker(
                            kind = Kind2.WORKFLOW,
                            id = toolId,
                            title = meta.first.ifBlank { "воркфлоу" },
                            subtitle = meta.second,
                            state = if (result.isBlank()) RunState.RUNNING else RunState.DONE,
                            report = result.oneLine(400),
                        )
                    )
                }
            }
        }
        // Работающие сверху: за ними и следят.
        return Report(out.sortedBy { if (it.state == RunState.RUNNING) 0 else 1 }, model)
    }

    private fun state(id: String, finished: Map<String, String>, result: String): RunState = when {
        finished[id] == "failed" -> RunState.FAILED
        finished[id] != null -> RunState.DONE
        // Ответ пришёл, а уведомления нет — значит вызов был синхронным.
        result.isNotBlank() && "background" !in result && "agentId" !in result -> RunState.DONE
        else -> RunState.RUNNING
    }

    /** `name` и `description` из meta-блока скрипта воркфлоу. */
    private fun metaOf(script: String): Pair<String, String> {
        val name = Regex("""name:\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.get(1).orEmpty()
        val desc = Regex("""description:\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.get(1).orEmpty()
        return name to desc
    }

    private fun JsonObject.str(field: String): String? =
        (this[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.bool(field: String): Boolean =
        (this[field] as? JsonPrimitive)?.content == "true"

    /** Ответ инструмента бывает строкой и массивом блоков. */
    private fun JsonObject.contentText(): String = when (val c = this["content"]) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
        else -> ""
    }
}
