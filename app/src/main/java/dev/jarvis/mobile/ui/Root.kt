package dev.jarvis.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.AppState
import dev.jarvis.mobile.Screen
import dev.jarvis.mobile.UiState
import dev.jarvis.mobile.model.title
import dev.jarvis.mobile.transport.Link

/**
 * Каркас приложения: шапка, состояние связи, сообщения и текущий экран.
 *
 * Настройки живут отдельным флагом, а не ещё одним `Screen`: это не место в
 * навигации по машинам, а модальное отступление в сторону, из которого всегда
 * возвращаются ровно туда, откуда пришли.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(state: UiState, app: AppState) {
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen) {
        SettingsScreen(state, app, onBack = { settingsOpen = false })
        return
    }

    val screen = state.screen
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            screenTitle(state),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        screenSubtitle(state)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (screen != Screen.Machines) {
                        TextButton(onClick = { app.back() }) { Text("‹ назад") }
                    }
                },
                actions = {
                    if (screen == Screen.Machines) {
                        TextButton(onClick = { settingsOpen = true }) { Text("Настройки") }
                    }
                },
            )
        }
    ) { padding ->
        // imePadding — иначе клавиатура накрывает поле ввода, и человек не
        // видит, что печатает. Одного adjustResize в манифесте мало: при
        // edge-to-edge окно вставками больше не двигают, их отдают верстке,
        // и учесть их обязана она.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LinkStrip(state.link, onRetry = { app.onResume() })
            // Причину провала уже сказала полоска связи — второй раз тем же
            // текстом это уже не сообщение, а шум.
            val linkWhy = (state.link as? Link.Failed)?.why
            state.error?.takeIf { it != linkWhy }?.let { Banner(it) }
            state.notice?.let { Notice(it) }
            state.launched?.let { LaunchedPane(it, app); return@Column }
            when (screen) {
                Screen.Machines -> MachinesScreen(state, app)
                is Screen.Sessions -> MachineTabs(state, app, screen.machineId, projects = false)
                is Screen.Projects -> MachineTabs(state, app, screen.machineId, projects = true)
                is Screen.Project -> ProjectScreen(state, app, screen.cwd)
                is Screen.Chat -> ChatScreen(state, app, screen.sessionId)
            }
        }
    }
}

/**
 * Две вкладки внутри машины: что идёт сейчас и где работали раньше.
 *
 * Вкладки, а не отдельный экран: машина уже выбрана, и возвращаться к её выбору
 * ради переключения между сессиями и проектами человеку незачем.
 */
@Composable
private fun MachineTabs(state: UiState, app: AppState, machineId: String, projects: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Segmented(
            items = listOf("Сессии", "Проекты"),
            selected = if (projects) 1 else 0,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PagePad, vertical = 8.dp),
            onSelect = { if (it == 0) app.showSessions(machineId) else app.openProjects(machineId) },
        )
        if (projects) ProjectsScreen(state, app, machineId) else SessionsScreen(state, app, machineId)
    }
}

private fun screenTitle(state: UiState): String = when (val screen = state.screen) {
    Screen.Machines -> "Машины"
    is Screen.Sessions -> state.machines.firstOrNull { it.id == screen.machineId }?.name ?: "Сессии"
    is Screen.Projects -> state.machines.firstOrNull { it.id == screen.machineId }?.name ?: "Проекты"
    is Screen.Project -> screen.cwd.trimEnd('/').substringAfterLast('/').ifEmpty { screen.cwd }
    is Screen.Chat -> state.sessions.firstOrNull { it.id == screen.sessionId }?.title() ?: "Чат"
}

/**
 * Вторая строка шапки: то, что иначе пришлось бы держать в голове.
 *
 * На машине — куда мы вообще подключены, в чате — что сейчас делает агент.
 */
private fun screenSubtitle(state: UiState): String? = when (val screen = state.screen) {
    Screen.Machines -> null
    is Screen.Sessions, is Screen.Projects -> {
        val id = (screen as? Screen.Sessions)?.machineId ?: (screen as Screen.Projects).machineId
        state.machines.firstOrNull { it.id == id }?.let { "${it.user}@${it.host}" }
    }
    is Screen.Project -> screen.cwd
    is Screen.Chat -> state.sessions.firstOrNull { it.id == screen.sessionId }?.let { statusWord(it.status) }
}
