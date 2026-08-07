package dev.jarvis.mobile

import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.Status
import dev.jarvis.mobile.model.tally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TallyTest {

    @Test
    fun `сессия попадает ровно в одну корзину`() {
        // Человек ответил в терминале самой машины: вопрос со статуса снялся,
        // а поле question осталось висеть — так работает редьюсер.
        val stuck = Session(id = "1", status = Status.WORKING, question = "продолжить?")
        val t = listOf(stuck).tally()
        assertEquals("вопрос важнее работы: за ним и лезут в телефон", 1, t.waiting)
        assertEquals("та же сессия не должна считаться дважды", 0, t.working)
    }

    @Test
    fun `считаются все три состояния`() {
        val t = listOf(
            Session(id = "1", status = Status.WAITING),
            Session(id = "2", status = Status.WORKING),
            Session(id = "3", status = Status.WORKING),
            Session(id = "4", status = Status.DONE),
            Session(id = "5", status = Status.IDLE),
        ).tally()
        assertEquals(1, t.waiting)
        assertEquals(2, t.working)
        assertEquals(1, t.done)
    }

    @Test
    fun `пустой список — не о чем говорить`() {
        assertFalse(emptyList<Session>().tally().any)
    }
}
