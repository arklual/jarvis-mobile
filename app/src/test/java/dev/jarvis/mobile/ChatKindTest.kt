package dev.jarvis.mobile

import dev.jarvis.mobile.model.Kind
import dev.jarvis.mobile.model.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JSONL — один объект НА СТРОКУ. Разбитый на строки JSON парсер обязан
 * пропускать: транскрипт пишется дописыванием, и «красивый» многострочный
 * объект в нём означал бы недописанную запись.
 */
class ChatKindTest {

    private fun tool(name: String, input: String) =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$name","input":$input}]}}"""

    /** Голое «Bash» не говорит ничего — лента должна показывать, чем занят агент. */
    @Test
    fun `инструмент подписан тем, что делает`() {
        val jsonl = listOf(
            tool("Bash", """{"command":"npm test -- --watch=false"}"""),
            tool("Read", """{"file_path":"/home/bob/proj/src/main.rs"}"""),
            tool("mcp__plugin_playwright__browser_click", "{}"),
        ).joinToString("\n")

        val items = Transcript.parse(jsonl)
        assertEquals(3, items.size)
        assertEquals("Bash", items[0].text)
        assertEquals("npm test -- --watch=false", items[0].detail)
        assertEquals(Kind.TOOL, items[0].kind)
        // из пути показываем имя файла: путь целиком в строку не влезает
        assertEquals("Read", items[1].text)
        assertEquals("main.rs", items[1].detail)
        // префикс MCP занимает половину строки и не несёт смысла
        assertEquals("browser_click", items[2].text)
    }

    @Test
    fun `субагент — отдельная строка, а не ещё один вызов`() {
        val jsonl = tool("Task", """{"subagent_type":"code-reviewer","description":"проверить дифф"}""")
        val item = Transcript.parse(jsonl).single()
        assertEquals(Kind.SUBAGENT, item.kind)
        assertEquals("code-reviewer", item.detail)
    }

    @Test
    fun `многострочный json пропускается — это недописанная запись`() {
        val broken = "{\"type\":\"assistant\",\"message\":{\"content\":[\n  {\"type\":\"text\""
        assertEquals(0, Transcript.parse(broken).size)
    }
}
