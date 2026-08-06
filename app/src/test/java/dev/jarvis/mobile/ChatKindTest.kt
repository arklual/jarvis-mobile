package dev.jarvis.mobile

import dev.jarvis.mobile.model.Kind
import dev.jarvis.mobile.model.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatKindTest {

    /** Голое «Bash» не говорит ничего — лента должна показывать, чем занят агент. */
    @Test
    fun `инструмент подписан тем, что делает`() {
        val jsonl = """
            {"type":"assistant","message":{"content":[
              {"type":"tool_use","name":"Bash","input":{"command":"npm test -- --watch=false"}},
              {"type":"tool_use","name":"Read","input":{"file_path":"/home/bob/proj/src/main.rs"}},
              {"type":"tool_use","name":"mcp__plugin_playwright__browser_click","input":{}}
            ]}}
        """.trimIndent()
        val items = Transcript.parse(jsonl)
        assertEquals(3, items.size)
        assertEquals("Bash" to "npm test -- --watch=false", items[0].text to items[0].detail)
        assertEquals("Read" to "main.rs", items[1].text to items[1].detail, )
        // префикс MCP занимает половину строки и не несёт смысла
        assertEquals("browser_click", items[2].text)
        assertEquals(Kind.TOOL, items[0].kind)
    }

    @Test
    fun `субагент — отдельная строка, а не ещё один вызов`() {
        val jsonl = """{"type":"assistant","message":{"content":[
            {"type":"tool_use","name":"Task","input":{"subagent_type":"code-reviewer","description":"проверить дифф"}}
        ]}}"""
        val item = Transcript.parse(jsonl).single()
        assertEquals(Kind.SUBAGENT, item.kind)
        assertEquals("code-reviewer", item.detail)
    }
}
