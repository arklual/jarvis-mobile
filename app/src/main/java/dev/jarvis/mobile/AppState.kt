package dev.jarvis.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.mobile.data.AuthKind
import dev.jarvis.mobile.data.Machine
import dev.jarvis.mobile.data.Machines
import dev.jarvis.mobile.data.SecretStore
import dev.jarvis.mobile.model.ChatItem
import dev.jarvis.mobile.model.Reducer
import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.Transcript
import dev.jarvis.mobile.model.sortedForList
import dev.jarvis.mobile.transport.KeyStep
import dev.jarvis.mobile.transport.MapKnownHosts
import dev.jarvis.mobile.transport.NodeClient
import dev.jarvis.mobile.transport.SshTunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что сейчас на экране. Экранов три — стек навигации тут был бы лишним. */
sealed interface Screen {
    data object Machines : Screen
    data class Sessions(val machineId: String) : Screen
    data class Chat(val machineId: String, val sessionId: String) : Screen
}

data class UiState(
    val screen: Screen = Screen.Machines,
    val machines: List<Machine> = emptyList(),
    val connecting: Boolean = false,
    val error: String? = null,
    val sessions: List<Session> = emptyList(),
    val chat: List<ChatItem> = emptyList(),
    val chatLoading: Boolean = false,
)

/**
 * Состояние приложения и вся работа с узлом.
 *
 * Курсор ленты живёт только в памяти: телефон не пытается быть вторым Jarvis —
 * он показывает, что происходит сейчас. История и полная картина остаются на
 * ноутбуке, и это сознательный выбор, а не незаконченность.
 */
class AppState(app: Application) : AndroidViewModel(app) {

    private val secrets = SecretStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var tunnel: SshTunnel? = null
    private var client: NodeClient? = null
    private var poller: Job? = null
    private var cursor: Long = 0
    private var registry: Map<String, Session> = emptyMap()

    init {
        _state.value = _state.value.copy(machines = Machines.decode(secrets.machinesRaw).items)
    }

    /* ================= машины ================= */

    fun saveMachine(machine: Machine, secret: String?, passphrase: String?) {
        val current = Machines.decode(secrets.machinesRaw).items.filterNot { it.id == machine.id }
        val next = Machines(current + machine)
        secrets.machinesRaw = Machines.encode(next)
        if (secret != null) secrets.setSecret(machine.id, secret)
        if (passphrase != null) secrets.setPassphrase(machine.id, passphrase)
        _state.value = _state.value.copy(machines = next.items)
    }

    fun removeMachine(id: String) {
        val next = Machines(Machines.decode(secrets.machinesRaw).items.filterNot { it.id == id })
        secrets.machinesRaw = Machines.encode(next)
        secrets.setSecret(id, null)
        secrets.setPassphrase(id, null)
        _state.value = _state.value.copy(machines = next.items)
    }

    /* ================= подключение ================= */

    fun open(machineId: String) {
        val machine = _state.value.machines.firstOrNull { it.id == machineId } ?: return
        disconnect()
        _state.value = _state.value.copy(
            screen = Screen.Sessions(machineId), connecting = true, error = null, sessions = emptyList(),
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { connect(machine) } }
            result.onFailure { e ->
                _state.value = _state.value.copy(connecting = false, error = human(e))
            }.onSuccess {
                _state.value = _state.value.copy(connecting = false, error = null)
                startPolling()
            }
        }
    }

    private fun connect(machine: Machine) {
        val secret = secrets.secret(machine.id).orEmpty()
        val auth = when (machine.authKind) {
            AuthKind.PASSWORD -> SshTunnel.Auth.Password(secret)
            AuthKind.KEY -> SshTunnel.Auth.PrivateKey(secret, secrets.passphrase(machine.id))
        }
        val t = SshTunnel(
            host = machine.host,
            port = machine.port,
            user = machine.user,
            auth = auth,
            remotePort = machine.nodePort,
            knownHosts = MapKnownHosts(secrets.knownHosts()),
        )
        val local = t.open()
        tunnel = t
        client = NodeClient("http://127.0.0.1:$local")
        cursor = 0
        registry = emptyMap()
        client?.hello() // рукопожатие: без него первая ошибка всплыла бы в опросе
    }

    private fun startPolling() {
        poller?.cancel()
        poller = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val c = client ?: break
                // try/catch, а не runCatching: из inline-лямбды нельзя выйти
                // через continue, а отмена корутины не должна выглядеть как
                // ошибка связи — её пробрасываем дальше нетронутой.
                val page = try {
                    c.events(cursor)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(error = human(e))
                    }
                    delay(3_000)
                    null
                }
                if (page == null) continue
                if (page.events.isNotEmpty()) {
                    registry = Reducer.apply(registry, page.events)
                    val list = registry.values.sortedForList()
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(sessions = list, error = null)
                    }
                }
                cursor = page.cursor
                if (page.events.isEmpty()) delay(1_000) // long-poll уже подождал
            }
        }
    }

    fun disconnect() {
        poller?.cancel()
        poller = null
        runCatching { tunnel?.close() }
        tunnel = null
        client = null
    }

    /* ================= чат ================= */

    fun openChat(machineId: String, sessionId: String) {
        _state.value = _state.value.copy(screen = Screen.Chat(machineId, sessionId), chat = emptyList(), chatLoading = true)
        val path = registry[sessionId]?.transcript
        if (path == null) {
            _state.value = _state.value.copy(chatLoading = false)
            return
        }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    // хвост: телефону нужен разговор, а не архив
                    val head = client?.file(path, Long.MAX_VALUE)
                    val size = head?.size ?: 0
                    val from = (size - TAIL_BYTES).coerceAtLeast(0)
                    val chunk = client?.file(path, from)
                    val text = chunk?.data.orEmpty()
                    Transcript.parse(if (from > 0) text.substringAfter('\n') else text)
                }.getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(chat = items.takeLast(120), chatLoading = false)
        }
    }

    fun reply(sessionId: String, text: String) {
        val pane = registry[sessionId]?.pane ?: return fail("Сессия не в tmux — ответить нельзя")
        val c = client ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.reply(pane, text) } }
                .onFailure { fail(human(it)) }
        }
    }

    /** Ответ на вопрос цифрой: простой пикер из одного вопроса. */
    fun answer(sessionId: String, option: Int) {
        val pane = registry[sessionId]?.pane ?: return fail("Сессия не в tmux — ответить нельзя")
        val c = client ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { c.keys(pane, listOf(KeyStep(key = option.toString()))) }
            }.onFailure { fail(human(it)) }
        }
    }

    fun back() {
        _state.value = when (_state.value.screen) {
            is Screen.Chat -> _state.value.copy(screen = Screen.Sessions(currentMachineId().orEmpty()))
            is Screen.Sessions -> { disconnect(); _state.value.copy(screen = Screen.Machines) }
            Screen.Machines -> _state.value
        }
    }

    private fun currentMachineId(): String? = when (val s = _state.value.screen) {
        is Screen.Sessions -> s.machineId
        is Screen.Chat -> s.machineId
        else -> null
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    /**
     * Человеческий текст вместо стектрейса: на телефоне читают именно его.
     *
     * Отдельно разобран «connection reset». Он значит не «сеть моргнула», а
     * почти всегда одно: туннель поднялся, но на той стороне порт узла никто
     * не слушает — sshd отказал в канале, и sshj закрыл локальный сокет.
     * Отличить это по тексту самому невозможно, поэтому объясняем.
     */
    private fun human(e: Throwable): String {
        val raw = e.message.orEmpty()
        val why = tunnel?.forwardError?.takeIf { it.isNotBlank() }
        val notListening = "Туннель есть, но порт узла на той машине никто не слушает.\n" +
            "Проверь там: systemctl --user show jarvis-node -p Environment — нужен " +
            "JARVIS_NODE_TCP=127.0.0.1:<порт>, и узел должен быть свежий."
        return when {
            raw.contains("Auth fail", true) -> "Не пустило: проверь пользователя и ключ или пароль"
            raw.contains("UnknownHost", true) -> "Не нашёл такой адрес"
            raw.contains("ECONNREFUSED", true) || raw.contains("Connection refused", true) -> notListening
            raw.contains("reset", true) || raw.contains("unexpected end of stream", true) ->
                if (why != null) "$notListening\n(ssh: $why)" else notListening
            raw.contains("administratively prohibited", true) ->
                "Сервер запретил проброс портов (AllowTcpForwarding no в sshd_config)"
            raw.contains("verif", true) -> "Отпечаток хоста изменился — подключение отклонено"
            raw.isBlank() -> e::class.java.simpleName
            else -> raw
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        const val TAIL_BYTES = 256L * 1024
    }
}
