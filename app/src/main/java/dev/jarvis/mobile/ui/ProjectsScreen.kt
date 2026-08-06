package dev.jarvis.mobile.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.AppState
import dev.jarvis.mobile.Launched
import dev.jarvis.mobile.UiState

/** Где на машине работали и куда завести новое. */
@Composable
fun ProjectsScreen(state: UiState, app: AppState, machineId: String) {
    var newPath by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PagePad, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PaperCard(Modifier.fillMaxWidth()) {
                SectionTitle("Новый проект")
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    placeholder = { Text("~/projects/my-app", style = MonoSmall) },
                    singleLine = true,
                    textStyle = MonoSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Каталог создастся сам. Сессия поднимется в tmux на той машине — " +
                        "терминал не откроется, чат появится в списке.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AgentButtons(enabled = newPath.isNotBlank()) { app.launch(newPath, it) }
            }
        }

        if (state.projectsLoading) {
            item { EmptyNote("Смотрю, где тут работали…") }
        } else if (state.projects.isEmpty()) {
            item { EmptyNote("Пока ни одного проекта — заведи новый выше.") }
        }

        items(state.projects, key = { it.dir }) { project ->
            val cwd = project.cwd ?: project.dir
            PaperCard(Modifier.fillMaxWidth(), onClick = { app.openProject(machineId, cwd) }) {
                Text(
                    cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    cwd,
                    style = MonoTiny,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${project.count} ${plural(project.count)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(PagePad)) }
    }
}

/** Чаты проекта. Заголовков у них нет — узел отдаёт оглавление, не смысл. */
@Composable
fun ProjectScreen(state: UiState, app: AppState, cwd: String) {
    val project = state.projects.firstOrNull { (it.cwd ?: it.dir) == cwd }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PagePad, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PaperCard(Modifier.fillMaxWidth()) {
                SectionTitle("Новая сессия здесь")
                Spacer(Modifier.height(8.dp))
                AgentButtons(enabled = true) { app.launch(cwd, it) }
            }
        }
        val sessions = project?.sessions.orEmpty()
        if (sessions.isEmpty()) {
            item { EmptyNote("Прошлых чатов в этом каталоге нет.") }
        }
        items(sessions, key = { it.id }) { s ->
            PaperCard(
                Modifier.fillMaxWidth(),
                onClick = { app.launch(cwd, project?.agent ?: "claude", s.id) },
            ) {
                Text("сессия ${s.id.take(8)}", style = MonoSmall)
                Spacer(Modifier.height(3.dp))
                Text(
                    "поднять продолжение на той машине",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(PagePad)) }
    }
}

/** Кем запускать. Claude — по умолчанию, поэтому он и залит краской. */
@Composable
private fun AgentButtons(enabled: Boolean, onLaunch: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = enabled, onClick = { onLaunch("claude") }) { Text("Claude") }
        OutlinedButton(enabled = enabled, onClick = { onLaunch("codex") }) { Text("Codex") }
    }
}

/**
 * Живой экран только что поднятой паны.
 *
 * Ради него всё и затевалось: раньше приложение говорило «поднял», а человек
 * не видел ни терминала, ни сессии — и не мог понять, случилось ли хоть что-то.
 * Пока агент не прислал первый хук, это единственное окно в происходящее.
 */
@Composable
fun LaunchedPane(launched: Launched, app: AppState) {
    Column(Modifier.fillMaxSize().padding(PagePad)) {
        Text("Запущено", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(3.dp))
        Text(
            launched.cwd,
            style = MonoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "Экран сессии на той машине. Как только агент отзовётся, он появится " +
                "в списке сессий сам.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Терминал не переносит строк: перенос ломает рамки и таблицы, которые
        // агент рисует псевдографикой. Поэтому прокрутка вбок, а не wrap.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(12.dp),
                )
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                launched.screen.ifBlank { "Жду первый экран…" },
                style = MonoTiny,
                softWrap = false,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Клавиши на случай, если агент всё-таки о чём-то спросил: пану мы
            // знаем, а значит ответить можно и отсюда.
            TextButton(onClick = { app.pressLaunched("Enter") }) { Text("Enter") }
            TextButton(onClick = { app.pressLaunched("1") }) { Text("1") }
            TextButton(onClick = { app.pressLaunched("2") }) { Text("2") }
            TextButton(onClick = { app.refreshScreen() }) { Text("Обновить") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { app.dismissLaunched() }) { Text("Готово") }
        }
    }
}
