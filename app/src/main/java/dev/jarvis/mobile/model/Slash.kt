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
    /**
     * Известные значения аргумента.
     *
     * Пустой список — команда уходит как есть. Непустой — палитра предлагает
     * ВЫБРАТЬ, а не печатать: набирать «sonnet» на телефоне, помня написание,
     * это не управление, а экзамен.
     */
    val args: List<String> = emptyList(),
) {
    val needsArg: Boolean get() = args.isNotEmpty()
}

object Slash {
    private val CLAUDE = listOf(
        SlashCommand("/model", "Модель", "чем думать", args = listOf("sonnet", "opus", "haiku")),
        SlashCommand("/effort", "Усилие", "сколько думать", args = listOf("low", "medium", "high", "max")),
        SlashCommand("/compact", "Сжать контекст", "освободить место, сохранив суть"),
        SlashCommand("/context", "Контекст", "чем занято окно"),
        SlashCommand("/cost", "Расход", "токены и стоимость сессии"),
        SlashCommand("/status", "Состояние", "версия, модель, аккаунт"),
        // /clear последним и с предупреждением: он выбрасывает разговор, и
        // соседство с безобидным /compact в списке — приглашение промахнуться
        SlashCommand("/clear", "Очистить", "выбросит разговор — назад не вернуть"),
    )

    private val CODEX = listOf(
        SlashCommand("/model", "Модель и усилие", "у Codex это один пикер"),
        SlashCommand("/compact", "Сжать контекст"),
        SlashCommand("/status", "Состояние"),
        SlashCommand("/clear", "Очистить", "выбросит разговор — назад не вернуть"),
    )

    fun forAgent(agent: String): List<SlashCommand> = if (agent == "codex") CODEX else CLAUDE

    /** Похоже ли на команду — по ней решаем, слать в пульт или как текст. */
    fun looksLikeCommand(text: String): Boolean = text.trimStart().startsWith("/")
}
