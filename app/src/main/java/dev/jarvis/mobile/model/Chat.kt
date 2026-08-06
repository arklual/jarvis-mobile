package dev.jarvis.mobile.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Одна реплика диалога — всё, что нужно телефону от транскрипта. */
data class ChatItem(val role: String, val text: String, val tool: Boolean = false)

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
                        "tool_use" -> out.add(ChatItem("assistant", o.text("name") ?: "инструмент", tool = true))
                    }
                }
            }
        }
        return out
    }

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
