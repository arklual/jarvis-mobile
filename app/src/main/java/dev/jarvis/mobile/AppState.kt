package dev.jarvis.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.mobile.data.AuthKind
import dev.jarvis.mobile.data.Machine
import dev.jarvis.mobile.data.Machines
import dev.jarvis.mobile.data.Prefs
import dev.jarvis.mobile.data.SecretStore
import dev.jarvis.mobile.model.Slash
import dev.jarvis.mobile.service.WatchService
import dev.jarvis.mobile.model.Artifact
import dev.jarvis.mobile.model.Artifacts
import dev.jarvis.mobile.model.ChatItem
import dev.jarvis.mobile.model.Picker
import dev.jarvis.mobile.model.PickerReader
import dev.jarvis.mobile.model.Reducer
import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.Transcript
import dev.jarvis.mobile.model.sortedForList
import dev.jarvis.mobile.model.Limits
import dev.jarvis.mobile.model.LimitsParser
import dev.jarvis.mobile.transport.KeyStep
import dev.jarvis.mobile.transport.Link
import dev.jarvis.mobile.transport.backoffSeconds
import dev.jarvis.mobile.transport.isTransient
import dev.jarvis.mobile.transport.MapKnownHosts
import dev.jarvis.mobile.model.Launch
import dev.jarvis.mobile.transport.NodeClient
import dev.jarvis.mobile.transport.RemoteProject
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
    /** Проекты машины: где на ней работали и куда можно завести новый. */
    data class Projects(val machineId: String) : Screen
    data class Project(val machineId: String, val cwd: String) : Screen
}

data class UiState(
    val screen: Screen = Screen.Machines,
    val machines: List<Machine> = emptyList(),
    /** Состояние связи — по нему рисуется и шапка, и всё, что зависит от узла. */
    val link: Link = Link.Idle,
    val connecting: Boolean = false,
    val error: String? = null,
    val limits: Limits? = null,
    val sessions: List<Session> = emptyList(),
    val chat: List<ChatItem> = emptyList(),
    val chatLoading: Boolean = false,
    /**
     * Артефакты считаются по ВСЕМУ прочитанному транскрипту, а не по видимому
     * хвосту ленты. Иначе в длинной сессии вкладка пуста: последние сто строк
     * — это команды и текст, а файлы агент правил раньше.
     */
    val artifacts: List<Artifact> = emptyList(),
    val commands: List<String> = emptyList(),
    val projects: List<RemoteProject> = emptyList(),
    val projectsLoading: Boolean = false,
    val notice: String? = null,
    /** Экран только что поднятой паны: пока сессии нет, это единственное окно. */
    val launched: Launched? = null,
    /** Открытый артефакт: файл, которого касался агент. */
    val artifact: ArtifactView? = null,
    /** Тумблеры уведомлений — читаются экраном настроек. */
    val prefs: PrefsView = PrefsView(),
    /**
     * Живой экран паны сессии, у которой открыт вопрос.
     *
     * Варианты ответа есть только там: хук их не приносит, и без экрана
     * телефону оставалось бы гадать, сколько кнопок рисовать.
     */
    val question: QuestionView? = null,
)

data class QuestionView(
    val sessionId: String,
    val screen: String = "",
    val picker: Picker = Picker(emptyList(), false),
    val loading: Boolean = true,
)

/** Содержимое артефакта: грузится по запросу, поэтому со своим состоянием. */
data class ArtifactView(
    val path: String,
    val text: String = "",
    val loading: Boolean = true,
    val error: String? = null,
)

/** Снимок настроек для экрана — Prefs сам по себе не наблюдаем. */
data class PrefsView(
    val notifyDone: Boolean = true,
    val notifyWaiting: Boolean = true,
    val keepAlive: Boolean = false,
)

/**
 * Только что запущенная сессия.
 *
 * Пока агент не прислал первый хук, в списке сессий её нет — а человек уже
 * нажал кнопку и вправе видеть, что происходит. Показываем экран паны: ровно
 * то, что увидел бы, подключившись к ней в терминале.
 */
data class Launched(val pane: String, val cwd: String, val screen: String = "")

/**
 * Состояние приложения и вся работа с узлом.
 *
 * Курсор ленты живёт только в памяти: телефон не пытается быть вторым Jarvis —
 * он показывает, что происходит сейчас. История и полная картина остаются на
 * ноутбуке, и это сознательный выбор, а не незаконченность.
 */
class AppState(app: Application) : AndroidViewModel(app) {

    private val secrets = SecretStore(app)
    private val prefs = Prefs(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var tunnel: SshTunnel? = null
    private var client: NodeClient? = null
    private var poller: Job? = null
    private var cursor: Long = 0
    private var registry: Map<String, Session> = emptyMap()
    /** Причина последнего обрыва — её показывает состояние «переподключаюсь». */
    private var lastWhy: String = ""

    /** `$HOME` той машины — из первого же пути проекта; нужен только для `~`. */
    private var homeHint: String? = null

    init {
        _state.value = _state.value.copy(
            machines = Machines.decode(secrets.machinesRaw).items,
            prefs = readPrefs(),
        )
    }

    private fun readPrefs() = PrefsView(prefs.notifyDone, prefs.notifyWaiting, prefs.keepAlive)

    /**
     * Тумблеры уведомлений.
     *
     * `keepAlive` не просто флаг: он поднимает фоновую службу. Без неё
     * уведомлений не бывает вовсе — Android останавливает корутины свёрнутого
     * приложения, а push-канала в этой схеме нет: узел стоит на чужой машине и
     * наружу не смотрит.
     */
    fun setPref(done: Boolean? = null, waiting: Boolean? = null, keepAlive: Boolean? = null) {
        done?.let { prefs.notifyDone = it }
        waiting?.let { prefs.notifyWaiting = it }
        keepAlive?.let {
            prefs.keepAlive = it
            prefs.watchMachine = currentMachineId() ?: _state.value.machines.firstOrNull()?.id
            val ctx = getApplication<Application>()
            if (it && prefs.watchMachine != null) WatchService.start(ctx) else WatchService.stop(ctx)
        }
        _state.value = _state.value.copy(prefs = readPrefs())
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
        cursor = 0 // другая машина — другая лента
        registry = emptyMap()
        _state.value = _state.value.copy(
            screen = Screen.Sessions(machineId),
            sessions = emptyList(),
            limits = null,
            error = null,
        )
        supervise(machine)
    }

    /**
     * Держать связь с машиной, пока экран её показывает.
     *
     * Один цикл на всё: подключение, опрос событий и переподключение. Раньше
     * это были три разных места, и обрыв в любом из них означал «зайди заново» —
     * на телефоне, где соединение рвётся от блокировки экрана, это худшее из
     * возможных поведений.
     */
    private fun supervise(machine: Machine) {
        poller?.cancel()
        poller = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                setLink(if (attempt == 0) Link.Connecting else Link.Retrying(attempt, lastWhy, 0))
                val ok = withContext(Dispatchers.IO) { runCatching { connect(machine) } }
                if (ok.isFailure) {
                    val why = human(ok.exceptionOrNull() ?: RuntimeException())
                    lastWhy = why
                    if (!isTransient(ok.exceptionOrNull()?.message.orEmpty())) {
                        setLink(Link.Failed(why))
                        return@launch // повтор не поможет: нужен человек
                    }
                    attempt++
                    val pause = backoffSeconds(attempt)
                    setLink(Link.Retrying(attempt, why, pause))
                    delay(pause * 1000L)
                    continue
                }
                attempt = 0
                setLink(Link.Online)
                loadLimits(fresh = false)
                // Опрос живёт до первой ошибки связи, дальше — снова цикл выше.
                val why = pollUntilBroken()
                if (why == null) return@launch // нас остановили
                lastWhy = why
                attempt = 1
                setLink(Link.Retrying(1, why, 1))
                delay(1_000)
            }
        }
    }

    /**
     * Тянуть события, пока связь жива. Возвращает причину обрыва либо `null`,
     * если цикл остановили снаружи.
     */
    private suspend fun pollUntilBroken(): String? {
        while (true) {
            val c = client ?: return "связь закрыта"
            val page = try {
                withContext(Dispatchers.IO) { c.events(cursor) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                return human(e)
            }
            if (page.gap) {
                // Узел честно сказал, что часть событий вытеснена. Реестр
                // пересобираем с нуля: дырявая картина хуже пустой.
                registry = emptyMap()
            }
            if (page.events.isNotEmpty()) {
                registry = Reducer.apply(registry, page.events)
                val list = registry.values.sortedForList()
                _state.value = _state.value.copy(sessions = list, error = null)
            }
            cursor = page.cursor
            if (page.events.isEmpty()) delay(POLL_FLOOR_MS)
        }
    }

    private fun setLink(link: Link) {
        _state.value = _state.value.copy(
            link = link,
            connecting = link.busy,
            error = (link as? Link.Failed)?.why,
        )
    }

    /** Лимиты аккаунта — их знает только та машина, где стоит агент. */
    fun loadLimits(fresh: Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { c.usage(fresh) }.getOrNull()
            }
            val parsed = text?.text?.takeIf { it.isNotBlank() }?.let { LimitsParser.parse(it) }
            if (parsed?.known == true) _state.value = _state.value.copy(limits = parsed)
        }
    }

    /**
     * Экран снова на глазах: телефон мог провести час в кармане с оборванным
     * соединением. Если связь не онлайн — поднимаем немедленно, не дожидаясь
     * очередной паузы backoff.
     */
    fun onResume() {
        val id = currentMachineId() ?: return
        if (_state.value.link.online) return
        val machine = _state.value.machines.firstOrNull { it.id == id } ?: return
        supervise(machine)
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
        // Курсор и реестр НЕ сбрасываем: переподключение — обычное дело на
        // телефоне (блокировка экрана, смена сети), и каждый раз перечитывать
        // ленту с нуля значит терять уже показанное и жечь трафик. Узел сам
        // скажет `gap`, если за это время что-то вытеснилось из буфера.
        client?.hello() // рукопожатие: без него первая ошибка всплыла бы в опросе
    }

    fun disconnect() {
        poller?.cancel()
        poller = null
        setLink(Link.Idle)
        runCatching { tunnel?.close() }
        tunnel = null
        client = null
    }

    /* ================= чат ================= */

    fun openChat(machineId: String, sessionId: String) {
        _state.value = _state.value.copy(
            screen = Screen.Chat(machineId, sessionId),
            chat = emptyList(),
            artifacts = emptyList(),
            commands = emptyList(),
            chatLoading = true,
            question = null,
        )
        if (registry[sessionId]?.question != null) loadQuestion(sessionId)
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
            _state.value = _state.value.copy(
                chat = items.takeLast(120),
                artifacts = Artifacts.from(items),
                commands = Artifacts.commands(items),
                chatLoading = false,
            )
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

    /**
     * Снять экран паны, чтобы увидеть настоящие варианты ответа.
     *
     * Дёргается, когда человек открыл чат сессии с вопросом. Без этого кнопки
     * пришлось бы выдумывать — и они появлялись бы даже там, где агент просто
     * закончил работу.
     */
    fun loadQuestion(sessionId: String) {
        val pane = registry[sessionId]?.pane ?: return
        val c = client ?: return
        _state.value = _state.value.copy(question = QuestionView(sessionId))
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { c.screen(pane).screen }.getOrDefault("")
            }
            val cur = _state.value.question ?: return@launch
            if (cur.sessionId != sessionId) return@launch // человек уже ушёл дальше
            _state.value = _state.value.copy(
                question = cur.copy(screen = text.trimEnd(), picker = PickerReader.read(text), loading = false),
            )
        }
    }

    fun clearQuestion() {
        _state.value = _state.value.copy(question = null)
    }

    /** Ответ на вопрос цифрой: простой пикер из одного вопроса. */
    fun answer(sessionId: String, option: Int) {
        val pane = registry[sessionId]?.pane ?: return fail("Сессия не в tmux — ответить нельзя")
        val c = client ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { c.keys(pane, listOf(KeyStep(key = option.toString()))) }
            }.onFailure { fail(human(it)) }
            // Пикер после нажатия перерисовывается — покажем, что получилось,
            // вместо того чтобы оставлять человека гадать, сработало ли.
            delay(600)
            loadQuestion(sessionId)
        }
    }

    /* ================= проекты ================= */

    fun openProjects(machineId: String) {
        _state.value = _state.value.copy(screen = Screen.Projects(machineId), projectsLoading = true, notice = null)
        val c = client ?: return failLoad("Нет связи с узлом")
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { c.projects() } }
            list.onSuccess {
                // Первый абсолютный путь выдаёт домашний каталог той машины —
                // отдельного запроса ради разворачивания `~` не нужно.
                homeHint = homeHint ?: it.firstNotNullOfOrNull { p -> p.cwd }
                    ?.split('/')?.take(3)?.joinToString("/")?.takeIf { h -> h.startsWith("/home") || h.startsWith("/Users") }
                _state.value = _state.value.copy(projects = it, projectsLoading = false, error = null)
            }.onFailure {
                _state.value = _state.value.copy(projectsLoading = false, error = human(it))
            }
        }
    }

    /** Вернуться к списку сессий, не разрывая связь: машина та же. */
    fun showSessions(machineId: String) {
        _state.value = _state.value.copy(screen = Screen.Sessions(machineId), notice = null)
    }

    fun openProject(machineId: String, cwd: String) {
        _state.value = _state.value.copy(screen = Screen.Project(machineId, cwd), notice = null)
    }

    /**
     * Поднять сессию на той машине. Терминала там нет и открывать нечего:
     * сессия появляется в `tmux -L jarvis` отсоединённой и дальше приезжает
     * событиями, как любая другая.
     */
    fun launch(cwd: String, agent: String, sessionId: String? = null) {
        val c = client ?: return failLoad("Нет связи с узлом")
        val path = cwd.trim()
        if (!path.startsWith("/") && !path.startsWith("~")) {
            return failLoad("Путь должен начинаться с / или ~")
        }
        _state.value = _state.value.copy(notice = "Поднимаю…", error = null, launched = null)
        viewModelScope.launch {
            val full = expand(path)
            val cmd = Launch.command(agent, sessionId)
            val res = withContext(Dispatchers.IO) {
                runCatching { c.launch(full, cmd, Launch.projectName(path)) }
            }
            res.onFailure {
                _state.value = _state.value.copy(notice = null, error = human(it))
            }.onSuccess { reply ->
                _state.value = _state.value.copy(
                    notice = null,
                    error = null,
                    launched = Launched(pane = reply.pane, cwd = full),
                )
                refreshScreen()
            }
        }
    }

    /**
     * Перечитать экран поднятой паны.
     *
     * Агент запускается несколько секунд, поэтому первый снимок почти всегда
     * пустой — обновляем несколько раз, а дальше по кнопке. Бесконечно
     * опрашивать не нужно: как только придёт первый хук, сессия появится в
     * списке и смотреть на экран станет незачем.
     */
    fun refreshScreen(times: Int = 4) {
        val pane = _state.value.launched?.pane ?: return
        val c = client ?: return
        viewModelScope.launch {
            repeat(times.coerceAtLeast(1)) { i ->
                if (i > 0) delay(1_500)
                val text = withContext(Dispatchers.IO) {
                    runCatching { c.screen(pane).screen }.getOrDefault("")
                }
                val cur = _state.value.launched ?: return@launch
                if (text.isNotBlank()) {
                    _state.value = _state.value.copy(launched = cur.copy(screen = text.trimEnd()))
                }
            }
        }
    }

    /** Убрать экран запуска: человек посмотрел и вернулся к списку. */
    fun dismissLaunched() {
        _state.value = _state.value.copy(launched = null)
    }

    /** Нажать клавишу в поднятой пане — ответить на вопрос первого запуска. */
    fun pressLaunched(key: String) {
        val pane = _state.value.launched?.pane ?: return
        val c = client ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.keys(pane, listOf(KeyStep(key = key))) } }
            refreshScreen(2)
        }
    }

    /**
     * `~` разворачиваем сами: узел его не раскрывает, а `mkdir -p '~/x'` создал
     * бы каталог с именем «~» в текущем месте — тихо и не там.
     */
    private fun expand(path: String): String {
        val home = homeHint ?: return path
        return if (path.startsWith("~")) home.trimEnd('/') + path.removePrefix("~") else path
    }

    private fun failLoad(message: String) {
        _state.value = _state.value.copy(projectsLoading = false, error = message)
    }

    /**
     * Слэш-команда в сессию.
     *
     * Отдельно от `reply`: пульт агента вставляет её иначе — с подтверждением
     * пикера, если он появится. Отправлять команду как обычный текст значит
     * получить её эхом в диалоге и никакого действия.
     */
    fun slash(sessionId: String, cmd: String) {
        val pane = registry[sessionId]?.pane ?: return fail("Сессия не в tmux — пульт недоступен")
        val c = client ?: return
        val text = cmd.trim()
        if (!Slash.looksLikeCommand(text)) return fail("Команда должна начинаться с /")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.control(pane, text) } }
                .onFailure { fail(human(it)) }
                .onSuccess { _state.value = _state.value.copy(notice = "Отправил $text") }
        }
    }

    /** Открыть файл, которого касался агент. Отдаёт его узел — файл на той машине. */
    fun openArtifact(path: String) {
        val c = client ?: return
        _state.value = _state.value.copy(artifact = ArtifactView(path = path))
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    // Хвост, а не файл целиком: телефону нужен взгляд, а не
                    // выгрузка мегабайтов через ssh.
                    val head = c.file(path, Long.MAX_VALUE)
                    val size = head?.size ?: 0
                    val from = (size - ARTIFACT_BYTES).coerceAtLeast(0)
                    val chunk = c.file(path, from) ?: return@runCatching null
                    if (from > 0) "…\n" + chunk.data.substringAfter('\n') else chunk.data
                }
            }
            val cur = _state.value.artifact ?: return@launch
            _state.value = _state.value.copy(
                artifact = res.fold(
                    onSuccess = { text ->
                        if (text == null) cur.copy(loading = false, error = "Файла уже нет на той машине")
                        else cur.copy(text = text, loading = false)
                    },
                    onFailure = { cur.copy(loading = false, error = human(it)) },
                ),
            )
        }
    }

    fun closeArtifact() {
        _state.value = _state.value.copy(artifact = null)
    }

    fun back() {
        // Открытый артефакт закрывается первым: он лежит поверх экрана, и
        // «назад» из него означает вернуться к тому же экрану, а не глубже.
        if (_state.value.artifact != null) {
            closeArtifact()
            return
        }
        _state.value = when (_state.value.screen) {
            is Screen.Chat -> _state.value.copy(screen = Screen.Sessions(currentMachineId().orEmpty()))
            is Screen.Project -> _state.value.copy(screen = Screen.Projects(currentMachineId().orEmpty()))
            is Screen.Projects -> _state.value.copy(screen = Screen.Sessions(currentMachineId().orEmpty()))
            is Screen.Sessions -> { disconnect(); _state.value.copy(screen = Screen.Machines) }
            Screen.Machines -> _state.value
        }
    }

    private fun currentMachineId(): String? = when (val s = _state.value.screen) {
        is Screen.Sessions -> s.machineId
        is Screen.Chat -> s.machineId
        is Screen.Projects -> s.machineId
        is Screen.Project -> s.machineId
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
        /** Пол опроса: узел уже держал запрос до 25 с, спешить некуда. */
        const val POLL_FLOOR_MS = 800L
        /** Сколько хвоста артефакта тянуть: экран телефона всё равно меньше. */
        const val ARTIFACT_BYTES = 128L * 1024
    }
}
