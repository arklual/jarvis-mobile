package dev.jarvis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.AppState
import dev.jarvis.mobile.Screen
import dev.jarvis.mobile.UiState
import dev.jarvis.mobile.data.AuthKind
import dev.jarvis.mobile.data.Machine
import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.Status
import dev.jarvis.mobile.model.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(state: UiState, app: AppState) {
    val screen = state.screen
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    when (screen) {
                        Screen.Machines -> "Машины"
                        is Screen.Sessions -> state.machines.firstOrNull { it.id == screen.machineId }?.name ?: "Сессии"
                        is Screen.Chat -> state.sessions.firstOrNull { it.id == screen.sessionId }?.title() ?: "Чат"
                    }
                )
            }, navigationIcon = {
                if (screen != Screen.Machines) TextButton(onClick = { app.back() }) { Text("‹ назад") }
            })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { Banner(it) }
            when (screen) {
                Screen.Machines -> MachinesScreen(state, app)
                is Screen.Sessions -> SessionsScreen(state, app, screen.machineId)
                is Screen.Chat -> ChatScreen(state, app, screen.sessionId)
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
    }
}

/* ================= машины ================= */

@Composable
private fun MachinesScreen(state: UiState, app: AppState) {
    var editing by remember { mutableStateOf<Machine?>(null) }
    if (editing != null) {
        MachineForm(editing!!, app) { editing = null }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (state.machines.isEmpty()) {
            Text(
                "Ни одной машины. Добавь ту, где стоит jarvis-node — телефон ходит к нему по SSH.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.machines, key = { it.id }) { m ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { app.open(m.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(m.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${m.user}@${m.host}:${m.port} · узел на 127.0.0.1:${m.nodePort}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { editing = m }) { Text("Править") }
                        TextButton(onClick = { app.removeMachine(m.id) }) { Text("Удалить") }
                    }
                }
                HorizontalDivider()
            }
        }
        Button(
            onClick = {
                editing = Machine(
                    id = System.currentTimeMillis().toString(),
                    name = "",
                    host = "",
                    user = "",
                )
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Добавить машину") }
    }
}

@Composable
private fun MachineForm(machine: Machine, app: AppState, done: () -> Unit) {
    var name by remember { mutableStateOf(machine.name) }
    var host by remember { mutableStateOf(machine.host) }
    var user by remember { mutableStateOf(machine.user) }
    var port by remember { mutableStateOf(machine.port.toString()) }
    var nodePort by remember { mutableStateOf(machine.nodePort.toString()) }
    var byPassword by remember { mutableStateOf(machine.authKind == AuthKind.PASSWORD) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(name, "Имя (как звать в списке)") { name = it }
        Field(host, "Адрес или алиас хоста") { host = it }
        Field(user, "Пользователь") { user = it }
        Field(port, "Порт SSH") { port = it }
        Field(nodePort, "Порт узла на петле (JARVIS_NODE_TCP)") { nodePort = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { byPassword = false }) {
                Text(if (byPassword) "Ключ" else "• Ключ")
            }
            TextButton(onClick = { byPassword = true }) {
                Text(if (byPassword) "• Пароль" else "Пароль")
            }
        }
        Field(secret, if (byPassword) "Пароль" else "Приватный ключ (PEM целиком)") { secret = it }
        if (!byPassword) Field(passphrase, "Пассфраза ключа (если есть)") { passphrase = it }
        Text(
            "Секреты лежат в защищённом хранилище телефона. Узел должен быть запущен " +
                "с JARVIS_NODE_TCP=127.0.0.1:<порт> — иначе к нему не пробросить туннель.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                app.saveMachine(
                    machine.copy(
                        name = name.ifBlank { host },
                        host = host.trim(),
                        user = user.trim(),
                        port = port.toIntOrNull() ?: 22,
                        nodePort = nodePort.toIntOrNull() ?: 7717,
                        authKind = if (byPassword) AuthKind.PASSWORD else AuthKind.KEY,
                    ),
                    secret = secret.ifBlank { null },
                    passphrase = passphrase.ifBlank { null },
                )
                done()
            }) { Text("Сохранить") }
            TextButton(onClick = done) { Text("Отмена") }
        }
    }
}

@Composable
private fun Field(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = label != "Приватный ключ (PEM целиком)",
        modifier = Modifier.fillMaxWidth(),
    )
}

/* ================= сессии ================= */

@Composable
private fun SessionsScreen(state: UiState, app: AppState, machineId: String) {
    if (state.connecting) {
        Text("Поднимаю туннель…", Modifier.padding(16.dp))
        return
    }
    if (state.sessions.isEmpty()) {
        Text(
            "Пока ни одной сессии. Узел отдаёт события по мере их появления — " +
                "запусти агента на той машине под tmux -L jarvis.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.sessions, key = { it.id }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { app.openChat(machineId, s.id) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(s.status)
                Column(Modifier.weight(1f)) {
                    Text(s.title(), fontWeight = FontWeight.Medium)
                    Text(
                        s.question ?: s.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

/** Состояние — формой и насыщенностью, а не радугой: так же, как на десктопе. */
@Composable
private fun StatusDot(status: Status) {
    val scheme = MaterialTheme.colorScheme
    val color = when (status) {
        Status.WAITING -> scheme.primary
        Status.WORKING -> scheme.onSurface
        Status.LIMIT -> scheme.outline
        Status.DONE -> Color.Transparent
        Status.IDLE -> scheme.outlineVariant
    }
    Box(
        Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

/* ================= чат ================= */

@Composable
private fun ChatScreen(state: UiState, app: AppState, sessionId: String) {
    var text by remember { mutableStateOf("") }
    val session = state.sessions.firstOrNull { it.id == sessionId }
    Column(Modifier.fillMaxSize()) {
        if (state.chatLoading) Text("Читаю транскрипт…", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(state.chat) { item -> Bubble(item.role, item.text, item.tool) }
        }
        session?.question?.let { q ->
            Column(Modifier.padding(12.dp)) {
                Text("Спрашивает: $q", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { n ->
                        TextButton(onClick = { app.answer(sessionId, n) }) { Text("$n") }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ответить агенту…") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (text.isNotBlank()) {
                    app.reply(sessionId, text.trim())
                    text = ""
                }
            }) { Text("→") }
        }
    }
}

@Composable
private fun Bubble(role: String, text: String, tool: Boolean) {
    val mine = role == "user"
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .background(
                    if (tool) scheme.surfaceVariant else if (mine) scheme.primary else scheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text,
                color = if (mine && !tool) scheme.onPrimary else scheme.onSurface,
                style = if (tool) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}
