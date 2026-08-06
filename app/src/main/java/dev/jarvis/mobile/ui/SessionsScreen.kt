package dev.jarvis.mobile.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.AppState
import dev.jarvis.mobile.UiState
import dev.jarvis.mobile.model.Limits
import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.Status
import dev.jarvis.mobile.model.Window
import dev.jarvis.mobile.model.title

/**
 * Что происходит на машине прямо сейчас.
 *
 * Сверху — лимиты: они одни на все сессии, и узнать про упёршийся потолок лучше
 * до того, как откроешь чат и не поймёшь, почему агент молчит.
 */
@Composable
fun SessionsScreen(state: UiState, app: AppState, machineId: String) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PagePad, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { LimitsCard(state.limits, app) }

        if (state.connecting && state.sessions.isEmpty()) {
            item { EmptyNote("Поднимаю туннель…") }
        } else if (state.sessions.isEmpty()) {
            item {
                EmptyNote(
                    "Пока ни одной сессии. Узел отдаёт события по мере их появления — " +
                        "запусти агента на той машине или заведи проект на соседней вкладке."
                )
            }
        }

        items(state.sessions, key = { it.id }) { session ->
            SessionCard(session) { app.openChat(machineId, session.id) }
        }
        item { Spacer(Modifier.height(PagePad)) }
    }
}

@Composable
private fun SessionCard(session: Session, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    PaperCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusMark(session.status)
            Spacer(Modifier.width(10.dp))
            Text(
                session.title(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Слово о состоянии — рядом с меткой, чтобы форму не приходилось
            // расшифровывать по памяти.
            Text(
                statusWord(session.status),
                style = MaterialTheme.typography.labelSmall,
                color = if (session.status == Status.WAITING) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            (session.question ?: session.detail).ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = if (session.question != null) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        session.cwd?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MonoTiny,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ================= лимиты ================= */

/**
 * Пятичасовое окно и недельное.
 *
 * «Неизвестно» и «0%» — разные вещи, и на экране они обязаны выглядеть
 * по-разному: увидев ноль там, где на самом деле нет данных, человек спокойно
 * запустит долгую задачу и упрётся в потолок на середине.
 */
@Composable
fun LimitsCard(limits: Limits?, app: AppState) {
    val known = limits?.known == true
    PaperCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Лимиты", Modifier.weight(1f))
            TextButton(onClick = { app.loadLimits(fresh = true) }) { Text("Обновить") }
        }
        if (!known) {
            Text(
                "неизвестно",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Их знает только та машина: спроси её кнопкой выше.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PaperCard
        }
        WindowRow("Пять часов", limits?.session)
        Spacer(Modifier.height(10.dp))
        WindowRow("Неделя", limits?.week)
        limits?.model?.let { (name, window) ->
            Spacer(Modifier.height(10.dp))
            WindowRow(name, window)
        }
    }
}

@Composable
private fun WindowRow(label: String, window: Window?) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (window == null) "неизвестно" else "${window.pct}%",
                style = MaterialTheme.typography.titleSmall,
                // Единственный случай, где краснеть уместно: потолок рядом.
                color = when {
                    window == null -> scheme.onSurfaceVariant
                    window.pct >= 90 -> scheme.error
                    else -> scheme.onSurface
                },
            )
        }
        Spacer(Modifier.height(5.dp))
        if (window == null) {
            // Пустая дорожка без заливки честнее нуля: видно, что мерить нечем.
            Box(Modifier.fillMaxWidth().height(6.dp))
        } else {
            Meter(window.pct / 100f)
            if (window.resets.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "сбросится ${window.resets}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
