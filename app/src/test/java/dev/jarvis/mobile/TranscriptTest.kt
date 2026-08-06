package dev.jarvis.mobile

import dev.jarvis.mobile.model.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    @Test
    fun `разбирает реплики и чипы инструментов`() {
        val jsonl = """
            {"type":"user","message":{"content":"привет"}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"здравствуй"},{"type":"tool_use","name":"Bash"}]}}
        """.trimIndent()
        val items = Transcript.parse(jsonl)
        assertEquals(3, items.size)
        assertEquals("user" to "привет", items[0].role to items[0].text)
        assertEquals("здравствуй", items[1].text)
        assertTrue(items[2].tool)
        assertEquals("Bash", items[2].text)
    }

    @Test
    fun `битые строки и служебные вставки не роняют разбор`() {
        val jsonl = """
            не json вовсе
            {"type":"user","isMeta":true,"message":{"content":"служебное"}}
            {"type":"user","message":{"content":"<system-reminder>шум</system-reminder>"}}
            {"type":"user","message":{"content":"живая реплика"}}
        """.trimIndent()
        val items = Transcript.parse(jsonl)
        assertEquals(1, items.size)
        assertEquals("живая реплика", items[0].text)
    }

    @Test
    fun `пустой ввод даёт пустой список`() {
        assertTrue(Transcript.parse("").isEmpty())
        assertTrue(Transcript.parse("\n\n").isEmpty())
    }
}
