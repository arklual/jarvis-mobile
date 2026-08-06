package dev.jarvis.mobile

import dev.jarvis.mobile.model.Launch
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchTest {

    /**
     * Сверка с настольной версией. Одна и та же кнопка на телефоне и на
     * ноутбуке обязана запускать одно и то же — иначе «продолжить сессию»
     * будет значить разное в зависимости от того, откуда нажали.
     */
    @Test
    fun `команды совпадают с настольными`() {
        // по умолчанию — «опасный режим»: агент поднимается отсоединённым,
        // и подтверждать оттуда каждое действие некому
        assertEquals("claude --dangerously-skip-permissions", Launch.command("claude"))
        assertEquals(
            "claude --resume abc --dangerously-skip-permissions",
            Launch.command("claude", "abc"),
        )
        assertEquals("codex --dangerously-bypass-approvals-and-sandbox", Launch.command("codex"))
        assertEquals("claude", Launch.command("claude", dangerous = false))
        assertEquals("codex resume abc", Launch.command("codex", "abc", dangerous = false))
    }

    @Test
    fun `имя проекта берётся из пути`() {
        assertEquals("my-app", Launch.projectName("/home/bob/my-app"))
        assertEquals("my-app", Launch.projectName("/home/bob/my-app/"))
        assertEquals("project", Launch.projectName("/"))
    }
}
