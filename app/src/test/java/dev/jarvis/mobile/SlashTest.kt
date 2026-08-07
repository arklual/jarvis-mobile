package dev.jarvis.mobile

import dev.jarvis.mobile.model.Slash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashTest {

    @Test
    fun `команда отличается от обычного ответа по первому символу`() {
        assertTrue(Slash.looksLikeCommand("/model sonnet"))
        assertTrue(Slash.looksLikeCommand("   /compact"))
        // текст, начинающийся со слэша в середине, командой не является:
        // отправить его в пульт значит потерять ответ человека
        assertFalse(Slash.looksLikeCommand("посмотри в src/main.rs"))
        assertFalse(Slash.looksLikeCommand(""))
    }

    @Test
    fun `у каждого агента свой набор и все команды начинаются со слэша`() {
        val claude = Slash.forAgent("claude")
        val codex = Slash.forAgent("codex")
        assertTrue(claude.all { it.cmd.startsWith("/") })
        assertTrue(codex.all { it.cmd.startsWith("/") })
        // у Codex нет отдельного /effort — он внутри пикера модели, и
        // предлагать несуществующую команду значит слать мусор в диалог
        assertTrue(claude.any { it.cmd == "/effort" })
        assertFalse(codex.any { it.cmd == "/effort" })
    }
    @Test
    fun `поиск идёт и по имени, и по описанию`() {
        val commands = Slash.forAgent("claude")
        // Человек помнит либо косую черту, либо словами — палитра обязана
        // находиться по обоим.
        assertTrue(commands.filter { it.matches("rewind") }.any { it.cmd == "/rewind" })
        assertTrue(commands.filter { it.matches("откат") }.any { it.cmd == "/rewind" })
        // Ведущая косая черта не должна мешать: её набирают по привычке.
        assertTrue(commands.filter { it.matches("/cost") }.any { it.cmd == "/cost" })
        assertEquals("пустой запрос показывает всё", commands.size, commands.count { it.matches("  ") })
    }

    @Test
    fun `необратимое помечено, обратимое — нет`() {
        val commands = Slash.forAgent("claude")
        assertTrue(commands.single { it.cmd == "/clear" }.danger)
        // Сжатие контекста сохраняет суть и не должно требовать подтверждения.
        assertFalse(commands.single { it.cmd == "/compact" }.danger)
    }
}
