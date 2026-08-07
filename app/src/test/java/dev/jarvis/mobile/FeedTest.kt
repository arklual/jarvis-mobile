package dev.jarvis.mobile

import dev.jarvis.mobile.model.ChatFeed
import dev.jarvis.mobile.model.RunState
import dev.jarvis.mobile.transport.FileChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Живая лента.
 *
 * Проверяем именно инкрементальность: лента собирается из кусков, которые режет
 * узел, а не из целого файла. Резать он их волен где угодно — в том числе
 * посреди строки.
 */
class FeedTest {

    private fun tool(name: String, input: String) =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t${input.hashCode()}","name":"$name","input":$input}]}}"""

    /** Кусок так, как его отдаёт узел: со смещениями и размером файла. */
    private fun chunk(data: String, from: Long, size: Long = (from + data.length)) =
        FileChunk(path = "/t.jsonl", from = from, next = from + data.length, size = size, data = data)

    private fun feed() = ChatFeed("s1", "/t.jsonl").also { it.startAt(0) }

    @Test
    fun `файлы собираются по полному пути и сортируются по частоте`() {
        val jsonl = listOf(
            tool("Edit", """{"file_path":"/p/src/main.rs"}"""),
            tool("Read", """{"file_path":"/p/README.md"}"""),
            tool("Write", """{"file_path":"/p/src/main.rs"}"""),
            tool("Bash", """{"command":"cargo test"}"""),
        ).joinToString("\n") + "\n"

        val arts = feed().accept(chunk(jsonl, 0))!!.artifacts
        assertEquals(2, arts.size)
        // путь целиком: узлу для открытия нужен именно он, а не имя из ленты
        assertEquals("/p/src/main.rs", arts[0].path)
        assertEquals(2, arts[0].touches)
        assertEquals("main.rs", arts[0].name)
        assertEquals("/p/src", arts[0].dir)
        // Bash — не файл, ему место в списке команд
        assertTrue(arts.none { it.path.contains("cargo") })
    }

    @Test
    fun `команды идут свежими сверху и без повторов`() {
        val jsonl = listOf(
            tool("Bash", """{"command":"cargo build"}"""),
            tool("Bash", """{"command":"cargo test"}"""),
            tool("Bash", """{"command":"cargo build"}"""),
        ).joinToString("\n") + "\n"
        assertEquals(listOf("cargo build", "cargo test"), feed().accept(chunk(jsonl, 0))!!.commands)
    }

    @Test
    fun `недописанная строка ждёт следующего куска`() {
        val line = tool("Edit", """{"file_path":"/p/a.rs"}""") + "\n"
        val f = feed()
        val head = line.substring(0, 20)
        // Узел отдал кусок посреди строки — разбирать нечего.
        assertNull("обрезанная строка не должна попасть в ленту", f.accept(chunk(head, 0)))
        val snap = f.accept(chunk(line.substring(20), head.length.toLong()))!!
        assertEquals(listOf("/p/a.rs"), snap.artifacts.map { it.path })
    }

    @Test
    fun `счётчики копятся между кусками, а не считаются заново`() {
        val f = feed()
        val a = tool("Edit", """{"file_path":"/p/a.rs"}""") + "\n"
        f.accept(chunk(a, 0))
        val snap = f.accept(chunk(a, a.length.toLong()))!!
        assertEquals(2, snap.artifacts.single().touches)
    }

    @Test
    fun `переписанный файл начинает ленту заново`() {
        val f = feed()
        val a = tool("Edit", """{"file_path":"/p/a.rs"}""") + "\n"
        f.accept(chunk(a, 0))
        // /clear: файл стал короче нашего смещения — узел зажимает from и отдаёт пустоту.
        val snap = f.accept(FileChunk(path = "/t.jsonl", from = 0, next = 0, size = 0, data = ""))!!
        assertTrue("старые артефакты должны уйти", snap.artifacts.isEmpty())
        assertTrue(snap.chat.isEmpty())
        assertEquals("читать надо с начала нового файла", 0L, f.offset)
    }

    @Test
    fun `перезапись ловится и по уменьшению размера`() {
        val f = feed()
        val a = tool("Edit", """{"file_path":"/p/a.rs"}""") + "\n"
        f.accept(chunk(a, 0, size = 10_000))
        // Файл переписали, он снова растёт — но стал меньше, чем был.
        val snap = f.accept(chunk(a, a.length.toLong(), size = 5_000))!!
        assertTrue(snap.artifacts.isEmpty())
    }

    @Test
    fun `параллельные работы переживают границу кусков`() {
        val call = """{"type":"assistant","message":{"model":"claude-opus-5","content":[{"type":"tool_use","id":"tu1","name":"Agent","input":{"description":"ревью","subagent_type":"code-reviewer"}}]}}"""
        val done = """{"type":"user","message":{"content":[{"type":"text","text":"<task-id>a1</task-id><status>completed</status>"}]}}"""
        val result = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tu1","content":"agentId: a1"}]}}"""
        val f = feed()
        val first = call + "\n"
        val w1 = f.accept(chunk(first, 0))!!.workers.single()
        assertEquals(RunState.RUNNING, w1.state)
        assertEquals("claude-opus-5", f.accept(chunk(result + "\n", first.length.toLong()))!!.model)
        // Уведомление приезжает отдельным куском — сшивается по идентификатору.
        val snap = f.accept(chunk(done + "\n", (first.length + result.length + 1).toLong()))!!
        assertEquals(RunState.DONE, snap.workers.single().state)
    }

    @Test
    fun `лента не растёт без границы`() {
        val f = feed()
        val line = tool("Bash", """{"command":"echo hi"}""") + "\n"
        var at = 0L
        repeat(ChatFeed.ITEM_CAP / 10 + 50) {
            f.accept(chunk(line.repeat(10), at))
            at += line.length * 10
        }
        val snap = f.accept(chunk(line, at))!!
        assertEquals(ChatFeed.CHAT_WINDOW, snap.chat.size)
        assertTrue("команд не больше предела", snap.commands.size <= ChatFeed.COMMAND_CAP)
    }
}
