package dev.jarvis.mobile

import dev.jarvis.mobile.model.Activity
import dev.jarvis.mobile.model.Kind2
import dev.jarvis.mobile.model.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формы строк взяты из настоящего транскрипта Claude Code: субагент отдаёт
 * `agentId:` в ответе, фоновая команда — «background with ID», а завершение
 * приходит отдельным уведомлением много позже.
 */
class ActivityTest {

    private fun use(id: String, name: String, input: String) =
        """{"type":"assistant","message":{"model":"claude-opus-5","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}"""

    private fun result(id: String, text: String) =
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":${quote(text)}}]}}"""

    private fun note(taskId: String, status: String) =
        """{"type":"user","message":{"content":[{"type":"text","text":"<task-notification>\n<task-id>$taskId</task-id>\n<status>$status</status>\n</task-notification>"}]}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `субагент виден с задачей, типом и моделью`() {
        val jsonl = listOf(
            use("t1", "Agent", """{"subagent_type":"general-purpose","description":"UI удалённых узлов"}"""),
            result("t1", "agentId: a315eca60e487baad\nThe agent is working in the background."),
        ).joinToString("\n")

        val w = Activity.parse(jsonl).workers.single()
        assertEquals(Kind2.SUBAGENT, w.kind)
        assertEquals("UI удалённых узлов", w.title)
        assertEquals("general-purpose", w.subtitle)
        assertEquals("claude-opus-5", w.model)
        // ответ пришёл, а уведомления о завершении нет — субагент ещё работает
        assertEquals(RunState.RUNNING, w.state)
        assertEquals("a315eca60e487baad", w.id)
    }

    @Test
    fun `уведомление о завершении закрывает работу`() {
        val jsonl = listOf(
            use("t1", "Agent", """{"description":"сборка"}"""),
            result("t1", "agentId: aXYZ"),
            note("aXYZ", "completed"),
        ).joinToString("\n")
        assertEquals(RunState.DONE, Activity.parse(jsonl).workers.single().state)

        val failed = listOf(
            use("t2", "Bash", """{"command":"cargo test","description":"тесты","run_in_background":true}"""),
            result("t2", "Command running in background with ID: bQQQ"),
            note("bQQQ", "failed"),
        ).joinToString("\n")
        val w = Activity.parse(failed).workers.single()
        assertEquals(Kind2.SHELL, w.kind)
        assertEquals(RunState.FAILED, w.state)
        assertEquals("cargo test", w.subtitle)
    }

    @Test
    fun `обычный Bash в список не попадает`() {
        // иначе список превратился бы в ту же ленту: фоновых команд единицы,
        // обычных — сотни
        val jsonl = use("t1", "Bash", """{"command":"ls","description":"список"}""")
        assertTrue(Activity.parse(jsonl).workers.isEmpty())
    }

    @Test
    fun `у воркфлоу показывается имя и описание, а не скрипт`() {
        val script = "export const meta = { name: 'review-changes', description: 'Проверить дифф' }\\nphase('Review')"
        val jsonl = use("t1", "Workflow", """{"script":${quote(script)}}""")
        val w = Activity.parse(jsonl).workers.single()
        assertEquals(Kind2.WORKFLOW, w.kind)
        assertEquals("review-changes", w.title)
        assertEquals("Проверить дифф", w.subtitle)
    }

    @Test
    fun `работающие идут первыми`() {
        val jsonl = listOf(
            use("t1", "Agent", """{"description":"давно закончил"}"""),
            result("t1", "agentId: aDONE"),
            note("aDONE", "completed"),
            use("t2", "Agent", """{"description":"ещё идёт"}"""),
            result("t2", "agentId: aLIVE"),
        ).joinToString("\n")
        assertEquals("ещё идёт", Activity.parse(jsonl).workers.first().title)
    }
}
