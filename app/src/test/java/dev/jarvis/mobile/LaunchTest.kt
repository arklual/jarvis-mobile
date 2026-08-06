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
        assertEquals("claude", Launch.command("claude"))
        assertEquals("claude --resume abc", Launch.command("claude", "abc"))
        assertEquals("codex", Launch.command("codex"))
        assertEquals("codex resume abc", Launch.command("codex", "abc"))
        assertEquals(
            "claude --resume abc --dangerously-skip-permissions",
            Launch.command("claude", "abc", dangerous = true),
        )
        assertEquals(
            "codex --dangerously-bypass-approvals-and-sandbox",
            Launch.command("codex", dangerous = true),
        )
    }

    @Test
    fun `имя проекта берётся из пути`() {
        assertEquals("my-app", Launch.projectName("/home/bob/my-app"))
        assertEquals("my-app", Launch.projectName("/home/bob/my-app/"))
        assertEquals("project", Launch.projectName("/"))
    }
}
