package dev.jarvis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.AppState
import dev.jarvis.mobile.UiState
import dev.jarvis.mobile.model.Artifact
import dev.jarvis.mobile.model.ChatItem
import dev.jarvis.mobile.model.Kind
import dev.jarvis.mobile.model.Slash
import dev.jarvis.mobile.model.SlashCommand
import dev.jarvis.mobile.model.Status

/**
 * Чат сессии: разговор и артефакты.
 *
 * Два раздела, а не два экрана: файлы и команды — это тот же разговор, только
 * пересказанный делами. Уходить ради них в отдельное место значит терять из
 * виду, кто там сейчас работает.
 */
@Composable
fun ChatScreen(state: UiState, app: AppState, sessionId: String) {
    val session = state.sessions.firstOrNull { it.id == sessionId }
    var tab by remember(sessionId) { mutableStateOf(0) }
    var draft by remember(sessionId) { mutableStateOf("") }
    var paletteOpen by remember { mutableStateOf(false) }

    // Открытый файл занимает экран целиком: моноширинному тексту нужна вся
    // ширина, а делить её с лентой — значит не показать толком ни того, ни того.
    val artifact = state.artifact
    if (artifact != null) {
        ArtifactViewer(
            path = artifact.path,
            text = artifact.text,
            loading = artifact.loading,
            error = artifact.error,
            onClose = { app.closeArtifact() },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Segmented(
            items = listOf("Разговор", "Артефакты"),
            selected = tab,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PagePad, vertical = 8.dp),
            onSelect = { tab = it },
        )
        if (session?.status == Status.WORKING) ThinkingStrip()

        if (tab == 1) {
            ArtifactsPane(state.artifacts, state.commands, app, Modifier.weight(1f))
            return@Column
        }

        if (state.chatLoading) {
            EmptyNote("Читаю транскрипт…")
        } else if (state.chat.isEmpty()) {
            EmptyNote(
                "Транскрипта пока нет. Он появится, как только агент напишет " +
                    "первую строку в этой сессии."
            )
        }
        Conversation(state.chat, Modifier.weight(1f))

        session?.question?.let { question ->
            QuestionBar(
                question = question,
                onOpen = { app.showPane(sessionId, "Спрашивает") },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InputBar(
            draft = draft,
            onDraft = { draft = it },
            onPalette = { paletteOpen = true },
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    // Команда уходит пультом, а не текстом: как обычный ответ она
                    // вернулась бы эхом в диалог и ничего бы не сделала.
                    if (Slash.looksLikeCommand(text)) app.slash(sessionId, text)
                    else app.reply(sessionId, text)
                    draft = ""
                }
            },
        )
    }

    if (paletteOpen) {
        SlashPalette(
            agent = session?.agent ?: "claude",
            onDismiss = { paletteOpen = false },
            onPick = { command ->
                paletteOpen = false
                // Аргумент выбран кнопкой — команда уходит целиком; иначе
                // уходит как есть, а пикер, который агент нарисует в ответ,
                // человек увидит на живом экране паны и пролистает там же.
                val arg = command.args.singleOrNull()
                app.slash(sessionId, if (arg != null) "${command.cmd} $arg" else command.cmd)
            },
        )
    }
}

/** «Модель думает» — строкой в шапке чата, пока сессия в работе. */
@Composable
private fun ThinkingStrip() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePad, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkingDots(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            "модель думает",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ================= лента ================= */

@Composable
private fun Conversation(chat: List<ChatItem>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Разговор читают с конца: новая строка должна оказаться перед глазами сама.
    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = PagePad, vertical = 6.dp),
    ) {
        items(chat) { item -> ChatRow(item) }
    }
}

/**
 * Строка ленты. Реплика — пузырь, вызов инструмента и субагент — узкая строка
 * с подписью: «Bash · npm test» вместо голого «Bash». Без подписи лента
 * выглядит так, будто агент долго молчал, хотя он всё это время работал.
 */
@Composable
private fun ChatRow(item: ChatItem) {
    if (item.kind == Kind.TEXT) {
        Bubble(item.role, item.text)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val subagent = item.kind == Kind.SUBAGENT
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Форма, а не цвет: субагент — отдельная работа, а не ещё один вызов
        Text(
            if (subagent) "▸" else "·",
            style = MonoSmall,
            color = if (subagent) scheme.primary else scheme.outline,
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (subagent) "субагент · ${item.text}" else item.text,
                style = MonoSmall,
                fontWeight = if (subagent) FontWeight.Medium else FontWeight.Normal,
                color = if (subagent) scheme.primary else scheme.onSurfaceVariant,
            )
            if (item.detail.isNotBlank()) {
                Text(
                    item.detail,
                    style = MonoTiny,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Реплика.
 *
 * Свои — залиты клевером и прижаты вправо, чужие — бумага с рамкой слева.
 * Ассиметричные скругления у «хвоста» пузыря: так видно, кто говорит, даже
 * когда экран читают боковым зрением.
 */
@Composable
private fun Bubble(role: String, text: String) {
    val mine = role == "user"
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                // Не во всю ширину: у пузыря должно оставаться поле, иначе
                // сторона говорящего перестаёт читаться.
                .widthIn(max = 320.dp)
                .background(
                    if (mine) scheme.primary else scheme.surfaceContainerLow,
                    if (mine) {
                        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                    } else {
                        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                    },
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            // Реплика агента — markdown: он пишет разметкой всегда, и показывать
            // её сырой значит заставлять читать `**` и ``` глазами. Свою
            // реплику не разбираем: человек писал текст, а не разметку.
            if (mine) {
                Text(
                    text,
                    color = scheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                MarkdownText(text, scheme.onSurface)
            }
        }
    }
}

/* ================= вопрос и ввод ================= */

/**
 * Агент встал на вопросе.
 *
 * Кнопок с цифрами здесь нет намеренно: сколько вариантов и что за ними — знает
 * только экран агента. Раньше их было четыре всегда, и они появлялись даже
 * там, где выбирать нечего. Открываем живой экран — там и варианты, и их текст.
 */
@Composable
private fun QuestionBar(question: String, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PagePad, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(8.dp))
            SectionTitle("Спрашивает")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpen) { Text("Показать экран") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            question,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraft: (String) -> Unit,
    onPalette: () -> Unit,
    onSend: () -> Unit,
) {
    val command = Slash.looksLikeCommand(draft)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        // Косая черта меняет судьбу строки, и человек вправе узнать об этом до
        // отправки, а не по странному эху в ленте.
        if (command) {
            Text(
                "уйдёт как команда, а не как ответ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 3.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Палитра — рядом с полем, а не в шапке: команду вспоминают в момент,
            // когда уже начали печатать ответ.
            OutlinedButton(
                onClick = onPalette,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("/", style = MonoSmall) }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Ответить агенту…") },
                textStyle = if (command) MonoSmall else MaterialTheme.typography.bodyLarge,
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) { Text("→") }
        }
    }
}

/* ================= палитра команд ================= */

/**
 * Список команд агента.
 *
 * Своего списка телефон не выдумывает: команда уходит в пану как есть, и
 * несуществующая обернётся мусором в диалоге. Всё, чего тут нет, дописывается
 * руками в поле ввода — оно понимает косую черту так же.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlashPalette(agent: String, onDismiss: () -> Unit, onPick: (SlashCommand) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PagePad)
                .padding(bottom = 28.dp)
        ) {
            SectionTitle("Команды · $agent")
            Spacer(Modifier.height(8.dp))
            Slash.forAgent(agent).forEach { command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(command.title, style = MaterialTheme.typography.titleSmall)
                        if (command.hint.isNotBlank()) {
                            Text(
                                command.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        command.cmd,
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Аргументы — кнопками, а не подсказкой в тексте: набирать
                // «sonnet» на телефоне, помня написание, это не управление.
                if (command.needsArg) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        command.args.forEach { arg ->
                            OutlinedButton(onClick = { onPick(command.copy(args = listOf(arg))) }) {
                                Text(arg, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/* ================= артефакты ================= */

/** Что агент трогал и что запускал — собранное из той же ленты. */
@Composable
private fun ArtifactsPane(
    files: List<Artifact>,
    commands: List<String>,
    app: AppState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = PagePad, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionTitle("Файлы") }
        if (files.isEmpty()) {
            item {
                Text(
                    "Ни одного файла: агент пока ничего не открывал и не правил.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(files, key = { it.path }) { file ->
            PaperCard(Modifier.fillMaxWidth(), onClick = { app.openArtifact(file.path) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "×${file.touches}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (file.dir.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        file.dir,
                        style = MonoTiny,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        // Обрезаем начало: конец пути говорит больше, чем /home
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            SectionTitle("Команды")
        }
        if (commands.isEmpty()) {
            item {
                Text(
                    "Агент ничего не запускал.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(commands) { command ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$", style = MonoSmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    command,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { Spacer(Modifier.height(PagePad)) }
    }
}

/**
 * Содержимое файла.
 *
 * Без переносов и с прокруткой вбок: код с перенесёнными строками читать
 * невозможно — отступы уезжают, и структура, ради которой в файл и полезли,
 * пропадает.
 */
@Composable
private fun ArtifactViewer(
    path: String,
    text: String,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = PagePad, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    path.trimEnd('/').substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    path,
                    style = MonoTiny,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onClose) { Text("закрыть") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            loading -> EmptyNote("Читаю файл…")
            error != null -> Text(
                error,
                Modifier.padding(PagePad),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(PagePad)
            ) {
                Text(text.ifBlank { "Файл пуст." }, style = MonoTiny, softWrap = false)
            }
        }
    }
}
