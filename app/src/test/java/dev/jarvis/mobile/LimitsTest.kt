package dev.jarvis.mobile

import dev.jarvis.mobile.model.LimitsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LimitsTest {

    /** Живой ответ узла — ровно в таком виде он и приходит. */
    private val real = """
        You are currently using your subscription to power your Claude Code usage

        Current session: 12% used · resets Aug 6, 2pm (UTC)
        Current week (all models): 27% used · resets Aug 10, 10:59pm (UTC)
        Current week (Fable): 0% used · resets Aug 10, 11pm (UTC)

        What's contributing to your limits usage?
    """.trimIndent()

    @Test
    fun `оба окна и модель разбираются`() {
        val l = LimitsParser.parse(real)
        assertEquals(12, l.session!!.pct)
        assertEquals("Aug 6, 2pm", l.session!!.resets)
        assertEquals(27, l.week!!.pct)
        // модель в скобках любая: там бывает Sonnet, Opus, Fable…
        assertEquals("Fable", l.model!!.first)
        assertEquals(0, l.model!!.second.pct)
        // узкое место — недельное окно, по нему и судить
        assertEquals(27, l.worst!!.pct)
    }

    @Test
    fun `неизвестное не превращается в ноль`() {
        // «нет данных» и «ноль процентов» на экране обязаны выглядеть по-разному,
        // иначе человек поверит, что лимит свободен
        val l = LimitsParser.parse("совсем другой текст")
        assertNull(l.session)
        assertNull(l.week)
        assertFalse(l.known)
    }

    @Test
    fun `частичный ответ не теряет то, что нашлось`() {
        val l = LimitsParser.parse("Current session: 91% used · resets Aug 6, 2pm (UTC)")
        assertEquals(91, l.session!!.pct)
        assertNull(l.week)
    }
}
