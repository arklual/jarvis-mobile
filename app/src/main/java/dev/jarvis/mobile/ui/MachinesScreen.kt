package dev.jarvis.mobile.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.AppState
import dev.jarvis.mobile.UiState
import dev.jarvis.mobile.data.AuthKind
import dev.jarvis.mobile.data.Machine
import dev.jarvis.mobile.Probe

/** Список машин: точка входа, и потому — самый спокойный экран приложения. */
@Composable
fun MachinesScreen(state: UiState, app: AppState) {
    var editing by remember { mutableStateOf<Machine?>(null) }
    var removing by remember { mutableStateOf<Machine?>(null) }

    val checking = state.probes.values.any { it.checking }
    // Сводка стареет: пока телефон лежал в кармане, всё могло измениться. Но и
    // ломиться по SSH на каждый показ экрана незачем — только если давно.
    LaunchedEffect(state.machines.size) {
        val fresh = state.probes.values.any { System.currentTimeMillis() - it.at < PROBE_FRESH_MS }
        if (state.machines.isNotEmpty() && !fresh) app.checkAll()
    }

    val target = editing
    if (target != null) {
        MachineForm(target, app) { editing = null }
        return
    }

    removing?.let { machine ->
        // Машина уносит с собой ключ из защищённого хранилища: восстановить её
        // «отменой» уже нельзя, поэтому спрашиваем.
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Удалить ${machine.name}?") },
            text = { Text("Ключ или пароль этой машины сотрётся из хранилища телефона.") },
            confirmButton = {
                TextButton(onClick = { app.removeMachine(machine.id); removing = null }) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (state.machines.isEmpty()) {
            EmptyNote(
                "Ни одной машины. Добавь ту, где стоит jarvis-node — телефон ходит " +
                    "к нему по SSH и показывает, что делают агенты."
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = PagePad, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.machines, key = { it.id }) { machine ->
                PaperCard(Modifier.fillMaxWidth(), onClick = { app.open(machine.id) }) {
                    Text(
                        machine.name.ifBlank { machine.host },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${machine.user}@${machine.host}:${machine.port}",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "узел 127.0.0.1:${machine.nodePort} · " +
                            if (machine.authKind == AuthKind.KEY) "ключ" else "пароль",
                        style = MonoTiny,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    ProbeLine(state.probes[machine.id])
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editing = machine }) { Text("Править") }
                        TextButton(onClick = { removing = machine }) { Text("Удалить") }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(PagePad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { app.checkAll() },
                modifier = Modifier.weight(1f),
                enabled = state.machines.isNotEmpty() && !checking,
            ) { Text(if (checking) "Смотрю…" else "Проверить все") }
            Button(
                onClick = {
                    editing = Machine(
                        id = System.currentTimeMillis().toString(),
                        name = "",
                        host = "",
                        user = "",
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Добавить") }
        }
    }
}

/**
 * Строка сводки на карточке машины.
 *
 * Числа, а не значки: «1 ждёт ответа» человек читает с одного взгляда и сразу
 * знает, стоит ли вообще заходить. Тишину проговариваем словом — пустая строка
 * неотличима от «ещё не проверяли», а это разные вещи.
 */
@Composable
private fun ProbeLine(probe: Probe?) {
    val scheme = MaterialTheme.colorScheme
    val (text, color) = when {
        probe == null -> "не проверялась" to scheme.onSurfaceVariant
        probe.checking -> "смотрю…" to scheme.onSurfaceVariant
        probe.error != null -> probe.error to scheme.error
        probe.quiet -> "тихо" to scheme.onSurfaceVariant
        else -> buildList {
            if (probe.waiting > 0) add("${probe.waiting} ждёт ответа")
            if (probe.working > 0) add("${probe.working} в работе")
            if (probe.done > 0) add("${probe.done} закончил")
        }.joinToString(" · ") to
            // Ждущая сессия — единственное, ради чего стоит тянуться к телефону
            // прямо сейчас; всё остальное идёт своим ходом.
            if (probe.waiting > 0) scheme.primary else scheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(2.dp))
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
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Куда ходить")
        Field(name, "Имя (как звать в списке)") { name = it }
        Field(host, "Адрес или алиас хоста") { host = it }
        Field(user, "Пользователь") { user = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(port, "Порт SSH", Modifier.weight(1f), numeric = true) { port = it }
            Field(nodePort, "Порт узла", Modifier.weight(1f), numeric = true) { nodePort = it }
        }

        Spacer(Modifier.height(2.dp))
        SectionTitle("Чем представляться")
        Segmented(
            items = listOf("Ключ", "Пароль"),
            selected = if (byPassword) 1 else 0,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { byPassword = it == 1 },
        )
        Field(
            secret,
            if (byPassword) "Пароль" else "Приватный ключ (PEM целиком)",
            single = byPassword,
        ) { secret = it }
        if (!byPassword) Field(passphrase, "Пассфраза ключа (если есть)") { passphrase = it }

        Text(
            "Секреты лежат в защищённом хранилище телефона. Узел должен быть запущен " +
                "с JARVIS_NODE_TCP=127.0.0.1:<порт> — иначе к нему не пробросить туннель.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = host.isNotBlank() && user.isNotBlank(),
                onClick = {
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
                },
            ) { Text("Сохранить") }
            TextButton(onClick = done) { Text("Отмена") }
        }
        Spacer(Modifier.height(PagePad))
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    single: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = single,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
        textStyle = if (numeric) MonoSmall else MaterialTheme.typography.bodyLarge,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Насколько сводка считается свежей: минута — цена SSH-рукопожатия к каждой. */
private const val PROBE_FRESH_MS = 60_000L
