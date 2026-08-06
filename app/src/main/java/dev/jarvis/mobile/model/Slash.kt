package dev.jarvis.mobile.model

/**
 * Команды агента, доступные с телефона.
 *
 * Полный список у агента свой и живёт в его сессии; телефон не пытается его
 * повторить — он даёт то, за чем реально лезут с телефона, плюс поле для любой
 * другой команды. Выдумывать несуществующее нельзя: команда уходит в пану как
 * есть, и опечатка обернётся мусором в диалоге.
 */
data class SlashCommand(
    val cmd: String,
    val title: String,
    val hint: String = "",
    /** Требует аргумент — палитра предложит его дописать, а не отправит сразу. */
    val needsArg: Boolean = false,
)

object Slash {
    private val CLAUDE = listOf(
        SlashCommand("/model", "Модель", "sonnet · opus · haiku", needsArg = true),
        SlashCommand("/effort", "Усилие", "low · medium · high · max", needsArg = true),
        SlashCommand("/compact", "Сжать контекст", "освободить место, сохранив суть"),
        SlashCommand("/clear", "Очистить", "начать разговор заново"),
        SlashCommand("/context", "Контекст", "чем занято окно"),
        SlashCommand("/cost", "Расход", "токены и стоимость сессии"),
        SlashCommand("/status", "Состояние", "версия, модель, аккаунт"),
        SlashCommand("/resume", "Другая сессия", needsArg = true),
    )

    private val CODEX = listOf(
        SlashCommand("/model", "Модель и усилие", "у Codex это один пикер", needsArg = true),
        SlashCommand("/compact", "Сжать контекст"),
        SlashCommand("/clear", "Очистить"),
        SlashCommand("/status", "Состояние"),
    )

    fun forAgent(agent: String): List<SlashCommand> = if (agent == "codex") CODEX else CLAUDE

    /** Похоже ли на команду — по ней решаем, слать в пульт или как текст. */
    fun looksLikeCommand(text: String): Boolean = text.trimStart().startsWith("/")
}
