package dev.jarvis.mobile.model

/**
 * Команда запуска агента — дословно та же, что собирает настольный Jarvis
 * (`src-tauri/src/launch.rs::agent_command`).
 *
 * Повторение здесь осознанное: узел команду не придумывает, он её исполняет, а
 * телефон в этот момент один. Расхождение с настольной версией означало бы, что
 * одна и та же кнопка на телефоне и на ноутбуке запускает разное, — это хуже,
 * чем дублирование четырёх строк.
 */
object Launch {
    fun command(agent: String, sessionId: String? = null, dangerous: Boolean = false): String =
        if (agent == "codex") {
            val flag = if (dangerous) " --dangerously-bypass-approvals-and-sandbox" else ""
            if (sessionId != null) "codex resume $sessionId$flag" else "codex$flag"
        } else {
            val flag = if (dangerous) " --dangerously-skip-permissions" else ""
            if (sessionId != null) "claude --resume $sessionId$flag" else "claude$flag"
        }

    /** Имя проекта из пути — оно же станет именем tmux-сессии на той машине. */
    fun projectName(cwd: String): String =
        cwd.trimEnd('/').substringAfterLast('/').ifEmpty { "project" }
}
