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
    /**
     * Команда уносит работу и назад её не вернуть.
     *
     * В списке такие соседствуют с безобидными, а промах на телефоне стоит
     * одного касания мимо — поэтому спрашиваем подтверждение. Прерывание или
     * сжатие контекста сюда не относятся: они обратимы.
     */
    val danger: Boolean = false,
) {
    val needsArg: Boolean get() = args.isNotEmpty()

    /** По чему ищем в палитре: и по имени, и по описанию — как кто помнит. */
    fun matches(query: String): Boolean {
        val q = query.trim().removePrefix("/").lowercase()
        if (q.isEmpty()) return true
        return cmd.contains(q, true) || title.contains(q, true) || hint.contains(q, true)
    }
}

object Slash {
    private val CLAUDE = listOf(
        // Списки сверены с настольной версией (backend::models / effort_levels),
        // а не составлены по памяти: пропущенная модель означает, что до неё
        // просто нельзя дотянуться с телефона.
        SlashCommand("/model", "Модель", "чем думать", args = listOf("fable", "opus", "sonnet", "haiku")),
        SlashCommand("/effort", "Усилие", "сколько думать", args = listOf("low", "medium", "high", "xhigh", "max")),
        SlashCommand("/compact", "Сжать контекст", "освободить место, сохранив суть"),
        SlashCommand("/context", "Контекст", "чем занято окно"),
        // Откат — то, ради чего с телефона лезут в панике: агент пошёл не туда,
        // а прерывание останавливает работу, но не отменяет сделанного. Сам по
        // себе безопасен: рисует список точек и ждёт выбора.
        SlashCommand("/rewind", "Откатить", "вернуться к точке до правок"),
        SlashCommand("/usage", "Лимиты", "сколько осталось в окне и за неделю"),
        SlashCommand("/cost", "Расход", "токены и стоимость сессии"),
        SlashCommand("/bashes", "Фоновые команды", "что ещё выполняется"),
        SlashCommand("/agents", "Субагенты", "какие настроены"),
        SlashCommand("/permissions", "Разрешения", "что агенту позволено без вопросов"),
        SlashCommand("/status", "Состояние", "версия, модель, аккаунт"),
        SlashCommand("/help", "Справка", "весь список команд самого агента"),
        // /clear последним и с предупреждением: он выбрасывает разговор, и
        // соседство с безобидным /compact в списке — приглашение промахнуться
        SlashCommand("/clear", "Очистить", "выбросит разговор — назад не вернуть", danger = true),
    )

    private val CODEX = listOf(
        SlashCommand("/model", "Модель и усилие", "у Codex это один пикер",
            args = listOf("gpt-5.5", "gpt-5-codex", "gpt-5")),
        SlashCommand("/compact", "Сжать контекст"),
        SlashCommand("/status", "Состояние"),
        SlashCommand("/clear", "Очистить", "выбросит разговор — назад не вернуть", danger = true),
    )

    fun forAgent(agent: String): List<SlashCommand> = if (agent == "codex") CODEX else CLAUDE

    /** Похоже ли на команду — по ней решаем, слать в пульт или как текст. */
    fun looksLikeCommand(text: String): Boolean = text.trimStart().startsWith("/")
}
