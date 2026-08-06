package dev.jarvis.mobile

import dev.jarvis.mobile.model.Reducer
import dev.jarvis.mobile.model.Status
import dev.jarvis.mobile.model.oneLine
import dev.jarvis.mobile.model.sortedForList
import dev.jarvis.mobile.transport.Recorded
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun event(body: String, at: Long = 0): Recorded =
        Recorded(cursor = at, at = at, envelope = json.parseToJsonElement(body) as JsonObject)

    @Test
    fun `вопрос поднимает сессию наверх и снимается ответом`() {
        var reg = Reducer.apply(emptyMap(), listOf(
            event("""{"event":"prompt","agent":"claude","tmux_pane":"%3",
                      "payload":{"session_id":"a","cwd":"/home/bob/proj","prompt":"сделай"}}""", at = 1),
        ))
        assertEquals(Status.WORKING, reg["a"]!!.status)
        assertEquals("proj", reg["a"]!!.project)

        reg = Reducer.apply(reg, listOf(
            event("""{"event":"notification","payload":{"session_id":"a","message":"Продолжить?"}}""", at = 2),
        ))
        assertEquals(Status.WAITING, reg["a"]!!.status)
        assertEquals("Продолжить?", reg["a"]!!.question)

        // ответили — вопрос снят, иначе он висел бы вечно
        reg = Reducer.apply(reg, listOf(
            event("""{"event":"prompt","payload":{"session_id":"a","prompt":"да"}}""", at = 3),
        ))
        assertNull(reg["a"]!!.question)
    }

    @Test
    fun `спрашивающая сессия идёт выше свежей работающей`() {
        val reg = Reducer.apply(emptyMap(), listOf(
            event("""{"event":"notification","payload":{"session_id":"ждёт","message":"?"}}""", at = 1),
            event("""{"event":"prompt","payload":{"session_id":"пашет","prompt":"go"}}""", at = 99),
        ))
        // за телефоном лезут ради вопроса, а не ради самой свежей строки
        assertEquals("ждёт", reg.values.sortedForList().first().id)
    }

    @Test
    fun `завершение сессии убирает её из списка`() {
        var reg = Reducer.apply(emptyMap(), listOf(
            event("""{"event":"prompt","payload":{"session_id":"a","prompt":"x"}}"""),
        ))
        reg = Reducer.apply(reg, listOf(
            event("""{"event":"session-end","payload":{"session_id":"a"}}"""),
        ))
        assertTrue(reg.isEmpty())
    }

    @Test
    fun `конверт без session_id не создаёт призрака`() {
        val reg = Reducer.apply(emptyMap(), listOf(
            event("""{"event":"prompt","payload":{}}"""),
            event("""{"event":"prompt"}"""),
        ))
        assertTrue(reg.isEmpty())
    }

    @Test
    fun `многострочный текст сжимается в одну строку`() {
        assertEquals("а б в", "а\n  б\t\tв".oneLine(50))
        assertTrue("x".repeat(200).oneLine(20).endsWith("…"))
    }
}
