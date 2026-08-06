package dev.jarvis.mobile

import dev.jarvis.mobile.model.VitalsReader
import org.junit.Assert.assertEquals
import org.junit.Test

class VitalsTest {

    /** Подвал TUI — единственное место, где effort написан честно. */
    @Test
    fun `усилие читается из подвала агента`() {
        val screen = """
            ⎿  Tip: Use /btw to ask a quick side question
            ────────────────────────────────────────────
            ❯
            ────────────────────────────────────────────
                                          ● high · /effort
        """.trimIndent()
        assertEquals("high", VitalsReader.fromScreen(screen).effort)
    }

    @Test
    fun `слова из вывода команды за подвал не принимаются`() {
        // «/effort» в теле вывода не должно становиться текущим усилием:
        // смотрим только хвост экрана, где подвал и живёт
        val screen = "поговорим про low · /effort\n" + "x\n".repeat(20)
        assertEquals("", VitalsReader.fromScreen(screen).effort)
    }

    @Test
    fun `идентификатор модели превращается в имя`() {
        assertEquals("Opus", VitalsReader.friendly("claude-opus-5"))
        assertEquals("Fable", VitalsReader.friendly("claude-fable-5"))
        assertEquals("Sonnet", VitalsReader.friendly("claude-sonnet-5"))
        assertEquals("", VitalsReader.friendly(""))
        // незнакомое показываем как есть, а не прячем
        assertEquals("что-то-новое", VitalsReader.friendly("что-то-новое"))
    }
}
