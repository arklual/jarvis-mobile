package dev.jarvis.mobile.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Что это за строка ленты: реплика, вызов инструмента или запуск субагента. */
enum class Kind { TEXT, TOOL, SUBAGENT }

/** Одна строка диалога — всё, что нужно телефону от транскрипта. */
data class ChatItem(
    val role: String,
    val text: String,
    val kind: Kind = Kind.TEXT,
    /** Подпись инструмента: чем он занят — команда, файл, шаблон. */
    val detail: String = "",
) {
    val tool: Boolean get() = kind != Kind.TEXT
}

/**
 * Разбор транскрипта Claude Code (JSONL) в реплики.
 *
 * Формат внутренний и дрейфует, поэтому разбираем defensive: неизвестное поле —
 * пропускаем, битая строка — пропускаем, никогда не падаем. Инструменты
 * показываем строкой-чипом: без них лента выглядит так, будто агент молчал.
 */
object Transcript {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<ChatItem> {
        val out = mutableListOf<ChatItem>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            if (obj.flag("isSidechain") || obj.flag("isMeta")) continue
            val message = obj["message"] as? JsonObject ?: continue
            when (obj.text("type")) {
                "user" -> content(message).forEach { block ->
                    block.textBlock()?.let { out.add(ChatItem("user", it)) }
                }
                "assistant" -> content(message).forEach { block ->
                    val o = block as? JsonObject ?: return@forEach
                    when (o.text("type")) {
                        "text" -> o.text("text")?.let { out.add(ChatItem("assistant", it)) }
                        "tool_use" -> out.add(toolItem(o))
                    }
                }
            }
        }
        return out
    }

    /**
     * Вызов инструмента → строка ленты.
     *
     * Подпись собирается так же, как в настольном Jarvis
     * (`transcript::short_tool_label`): голое «Bash» не говорит ничего, а
     * «Bash · npm test» — говорит всё. Ключи перебираются по убыванию
     * содержательности, и первый найденный выигрывает.
     */
    private fun toolItem(block: JsonObject): ChatItem {
        val raw = block.text("name") ?: "tool"
        // mcp__plugin_playwright__browser_click → browser_click: префикс MCP
        // занимает половину строки и не несёт смысла
        val name = raw.removePrefix("mcp__").substringAfterLast("__").ifBlank { raw }
        val input = block["input"] as? JsonObject
        val detail = DETAIL_KEYS.firstNotNullOfOrNull { key ->
            input?.text(key)?.let { if (key == "file_path") it.trimEnd('/').substringAfterLast('/') else it }
        }.orEmpty()
        // Task — это субагент: у него своя строка, потому что он не «вызов
        // инструмента», а отдельная работа, которую агент кому-то поручил
        val kind = if (raw == "Task") Kind.SUBAGENT else Kind.TOOL
        val subject = if (kind == Kind.SUBAGENT) {
            input?.text("subagent_type") ?: input?.text("description") ?: detail
        } else {
            detail
        }
        return ChatItem("assistant", name, kind, subject.oneLine(72))
    }

    private val DETAIL_KEYS = listOf("command", "file_path", "pattern", "url", "description")

    /** content бывает строкой и массивом блоков — Claude пишет и так, и так. */
    private fun content(message: JsonObject): List<kotlinx.serialization.json.JsonElement> =
        when (val c = message["content"]) {
            is JsonArray -> c.toList()
            is JsonPrimitive -> listOf(c)
            else -> emptyList()
        }

    private fun kotlinx.serialization.json.JsonElement.textBlock(): String? = when (this) {
        is JsonPrimitive -> content.takeIf { it.isNotBlank() && !it.startsWith("<") }
        is JsonObject -> if (text("type") == "text") text("text") else null
        else -> null
    }

    private fun JsonObject.text(field: String): String? =
        (this[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.flag(field: String): Boolean =
        (this[field] as? JsonPrimitive)?.content == "true"
}
