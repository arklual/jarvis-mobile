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
import dev.jarvis.mobile.model.ChatFeed
import dev.jarvis.mobile.model.ChatItem
import dev.jarvis.mobile.model.Picker
import dev.jarvis.mobile.model.PickerReader
import dev.jarvis.mobile.model.Reducer
import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.tally
import dev.jarvis.mobile.model.Vitals
import dev.jarvis.mobile.model.VitalsReader
import dev.jarvis.mobile.model.Worker
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
    /** Сводка по каждой машине: ключ — id машины. */
    val probes: Map<String, Probe> = emptyMap(),
    /** Состояние связи — по нему рисуется и шапка, и всё, что зависит от узла. */
    val link: Link = Link.Idle,
    val connecting: Boolean = false,
    val error: String? = null,
    val limits: Limits? = null,
    val sessions: List<Session> = emptyList(),
    val chat: List<ChatItem> = emptyList(),
    /** Номер первой видимой записи: по нему список опознаёт строки. */
    val chatFrom: Int = 0,
    val chatLoading: Boolean = false,
    /**
     * Артефакты считаются по ВСЕМУ прочитанному транскрипту, а не по видимому
     * хвосту ленты. Иначе в длинной сессии вкладка пуста: последние сто строк
     * — это команды и текст, а файлы агент правил раньше.
     */
    val artifacts: List<Artifact> = emptyList(),
    val commands: List<String> = emptyList(),
    /**
     * Параллельные работы: субагенты, фоновые команды, воркфлоу.
     *
     * В ленте они выглядят одинаковыми чипами «Agent» и «Bash», и понять, кто
     * чем занят и кто ещё жив, по ней нельзя.
     */
    val workers: List<Worker> = emptyList(),
    /** Чем и как агент думает: модель из транскрипта, effort с его экрана. */
    val vitals: Vitals = Vitals(),
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
     * Живой экран паны сессии — единственное честное окно в происходящее.
     *
     * Один и тот же экран показывает и вопрос агента, и то, что он нарисовал в
     * ответ на команду. Слать `/model` вслепую и гадать, открылся ли пикер, —
     * не управление; а варианты выбора существуют только на экране, хук их не
     * приносит.
     */
    val pane: PaneView? = null,
)

/**
 * Беглый опрос машины: что на ней происходит прямо сейчас.
 *
 * Телефон достают, чтобы узнать «кто меня ждёт», а не «что на машине N».
 * Раньше ответ на этот вопрос требовал подключиться к каждой машине по
 * очереди — то есть его просто не получали.
 */
data class Probe(
    val checking: Boolean = false,
    val waiting: Int = 0,
    val working: Int = 0,
    val done: Int = 0,
    /** Встали и сами не поедут: лимит или сорвавшийся ход. */
    val stuck: Int = 0,
    /** Всего сессий: без него «ничего не происходит» неотличимо от «пусто». */
    val total: Int = 0,
    /** Не дозвонились: причина короткой строкой, чинить-то всё равно человеку. */
    val error: String? = null,
    val at: Long = 0,
) {
    val answered: Boolean get() = error == null && at > 0
    val busy: Boolean get() = waiting > 0 || working > 0 || done > 0 || stuck > 0
    /** Требуется человек: либо спрашивают, либо встали. */
    val needsYou: Boolean get() = waiting > 0 || stuck > 0
    /** Совсем ничего: ни одной сессии. Не то же самое, что «все спят». */
    val quiet: Boolean get() = answered && !busy && total == 0
}

data class PaneView(
    val sessionId: String,
    /** Зачем открыли: «Спрашивает» или «/model» — человеку нужен контекст. */
    val title: String,
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
    /** Работа встала сама: лимит или сорвавшийся ход. */
    val notifyStuck: Boolean = true,
    val keepAlive: Boolean = false,
    /**
     * Приложение исключено из оптимизации батареи.
     *
     * Производители Android агрессивно усыпляют фон, и служба переднего плана
     * от этого не спасает. Без исключения уведомления просто перестают
     * приходить — молча, что человек и принимает за поломку приложения.
     */
    val batteryExempt: Boolean = true,
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
    /** Обход машин для сводки: он длинный, и уходя с экрана его гасят. */
    private var probeJob: Job? = null
    /**
     * Туннель текущей проверки.
     *
     * Отмена корутины не прерывает блокирующий ввод-вывод: SSH-рукопожатие и
     * чтение доработали бы до таймаута — до полуминуты живой связи после того,
     * как человек ушёл с экрана. Закрытие туннеля выбивает их сразу.
     */
    @Volatile
    private var inflight: SshTunnel? = null
    /** Дочитывание открытого чата: живёт ровно пока чат на экране. */
    private var chatTail: Job? = null
    /**
     * Разобранная лента открытого чата.
     *
     * Переживает уход в фон: гаснет экран, поворот, возврат из системных
     * настроек — всё это ON_STOP, и перечитывать из-за каждого 256 КиБ через
     * ssh незачем.
     */
    private var feed: ChatFeed? = null
    /** Приложение на переднем плане: только тогда чат имеет право дочитываться. */
    private var foreground = true
    private var cursor: Long = 0
    private var registry: Map<String, Session> = emptyMap()
    /** Причина последнего обрыва — её показывает состояние «переподключаюсь». */
    private var lastWhy: String = ""
    /** Сессия из уведомления: откроется, как только появится в реестре. */
    private var pendingChat: String? = null

    /** `$HOME` той машины — из первого же пути проекта; нужен только для `~`. */
    private var homeHint: String? = null

    init {
        _state.value = _state.value.copy(
            machines = Machines.decode(secrets.machinesRaw).items,
            prefs = readPrefs(),
        )
        // Тумблер переживает перезапуск приложения, а служба — нет: её
        // запускало ТОЛЬКО переключение. Человек видел включённый флаг и не
        // получал ничего, что хуже отсутствия уведомлений — на них полагаются.
        if (prefs.keepAlive && prefs.watchMachine != null) WatchService.start(app)
    }

    private fun readPrefs() = PrefsView(
        notifyDone = prefs.notifyDone,
        notifyWaiting = prefs.notifyWaiting,
        notifyStuck = prefs.notifyStuck,
        keepAlive = prefs.keepAlive,
        batteryExempt = batteryExempt(),
    )

    /** Спрашиваем систему, а не помним сами: человек мог отозвать исключение. */
    private fun batteryExempt(): Boolean {
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(true)
    }

    /** Перечитать состояние исключения — после возврата из системных настроек. */
    fun refreshPrefs() {
        _state.value = _state.value.copy(prefs = readPrefs())
    }

    /**
     * Тумблеры уведомлений.
     *
     * `keepAlive` не просто флаг: он поднимает фоновую службу. Без неё
     * уведомлений не бывает вовсе — Android останавливает корутины свёрнутого
     * приложения, а push-канала в этой схеме нет: узел стоит на чужой машине и
     * наружу не смотрит.
     */
    fun setPref(
        done: Boolean? = null,
        waiting: Boolean? = null,
        stuck: Boolean? = null,
        keepAlive: Boolean? = null,
    ) {
        done?.let { prefs.notifyDone = it }
        waiting?.let { prefs.notifyWaiting = it }
        stuck?.let { prefs.notifyStuck = it }

        // Включённый тумблер уведомлений без фоновой связи не делает НИЧЕГО:
        // приложение свёрнуто, корутины остановлены, узнать о завершении
        // неоткуда. Человек при этом видит включённый флаг и ждёт уведомлений —
        // худший исход из возможных. Поэтому связь поднимается вместе с ними;
        // цену (постоянное уведомление системы) экран настроек проговаривает.
        val wantAlerts = prefs.notifyDone || prefs.notifyWaiting || prefs.notifyStuck
        val want = keepAlive ?: (if (wantAlerts) true else prefs.keepAlive)
        // А выключив последний тумблер уведомлений, держать связь незачем.
        val effective = if (!wantAlerts && keepAlive == null) false else want

        if (effective != prefs.keepAlive || keepAlive != null) {
            prefs.keepAlive = effective
            prefs.watchMachine = currentMachineId() ?: _state.value.machines.firstOrNull()?.id
            val ctx = getApplication<Application>()
            if (effective && prefs.watchMachine != null) WatchService.start(ctx) else WatchService.stop(ctx)
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
        // Машину правили — прежняя сводка снята с другого адреса, и держать
        // её на карточке значит показывать чужой ответ.
        _state.update { it.copy(machines = next.items, probes = it.probes - machine.id) }
    }

    fun removeMachine(id: String) {
        val next = Machines(Machines.decode(secrets.machinesRaw).items.filterNot { it.id == id })
        secrets.machinesRaw = Machines.encode(next)
        secrets.setSecret(id, null)
        secrets.setPassphrase(id, null)
        // Сводку уносим с собой: иначе она продолжает считаться в занятости и
        // может заблокировать кнопку обхода.
        _state.update { it.copy(machines = next.items, probes = it.probes - id) }
    }

    /* ================= подключение ================= */

    fun open(machineId: String) {
        val machine = _state.value.machines.firstOrNull { it.id == machineId } ?: return
        // Обход машин отменяем: человек уже выбрал, куда идти, и делить с ним
        // радиоканал ради сводки, которую он больше не увидит, незачем.
        probeJob?.cancel()
        probeJob = null
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
                _state.update { it.copy(sessions = list, error = null) }
                // Дочитывание могло не начаться (транскрипта ещё не было),
                // умереть или остаться на старом файле — resumeTail разберётся
                // сам, а живое дочитывание он не трогает.
                resumeTail()
                // Сессия из уведомления дождалась своего появления в реестре.
                pendingChat?.let { sid ->
                    if (registry.containsKey(sid)) {
                        pendingChat = null
                        openChat(currentMachineId().orEmpty(), sid)
                    }
                }
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
     * Ушли с экрана: гасим дочитывание чата.
     *
     * Сам поллер событий не трогаем — за уведомления отвечает служба, а список
     * сессий должен быть свежим сразу по возвращении.
     */
    fun onBackground() {
        foreground = false
        probeJob?.cancel()
        probeJob = null
        chatTail?.cancel()
        chatTail = null
    }

    /**
     * Экран снова на глазах: телефон мог провести час в кармане с оборванным
     * соединением. Если связь не онлайн — поднимаем немедленно, не дожидаясь
     * очередной паузы backoff.
     */
    fun onResume() {
        foreground = true
        // Исключение из оптимизации батареи человек мог выдать (или отозвать) в
        // системных настройках — узнать об этом можно только спросив заново.
        refreshPrefs()
        // Вернулись в открытый чат — дочитывание надо поднять заново. Лента при
        // этом не перечитывается: накопитель пережил паузу.
        resumeTail()
        // На списке машин сводка за час в кармане устарела вся. Композиция
        // уход в фон переживает, поэтому сама она не обновится.
        if (_state.value.screen is Screen.Machines) checkAll()
        val id = currentMachineId() ?: return
        if (_state.value.link.online) return
        val machine = _state.value.machines.firstOrNull { it.id == id } ?: return
        supervise(machine)
    }

    /** Туннель к машине. Порт локальной стороны выбирает система — их бывает много. */
    private fun tunnelFor(machine: Machine): SshTunnel {
        val secret = secrets.secret(machine.id).orEmpty()
        val auth = when (machine.authKind) {
            AuthKind.PASSWORD -> SshTunnel.Auth.Password(secret)
            AuthKind.KEY -> SshTunnel.Auth.PrivateKey(secret, secrets.passphrase(machine.id))
        }
        return SshTunnel(
            host = machine.host,
            port = machine.port,
            user = machine.user,
            auth = auth,
            remotePort = machine.nodePort,
            knownHosts = MapKnownHosts(secrets.knownHosts()),
        )
    }

    private fun connect(machine: Machine) {
        val t = tunnelFor(machine)
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
        chatTail?.cancel()
        chatTail = null
        feed = null
        setLink(Link.Idle)
        runCatching { tunnel?.close() }
        tunnel = null
        client = null
    }

    /* ================= сводка по машинам ================= */

    /**
     * Обойти все машины и узнать, где что происходит.
     *
     * По очереди, а не разом: каждая проверка — это SSH-рукопожатие, и три
     * одновременных на телефоне означают три радиосеанса и заметный разряд ради
     * экрана, на который смотрят пару секунд.
     */
    fun checkAll() {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            for (machine in _state.value.machines) {
                setProbe(machine.id, Probe(checking = true))
                val result = withContext(Dispatchers.IO) { probe(machine) }
                setProbe(machine.id, result)
            }
        }
        // Отменённый обход оставлял бы карточки в «смотрю…» навсегда, а кнопка
        // считает по ним занятость — то есть одно нажатие на машину посреди
        // обхода выключало её насмерть.
        probeJob?.invokeOnCompletion {
            inflight?.let { t -> runCatching { t.close() } }
            inflight = null
            _state.update { st ->
                st.copy(probes = st.probes.mapValues { (_, p) -> if (p.checking) Probe() else p })
            }
        }
    }

    private fun probe(machine: Machine): Probe {
        val now = System.currentTimeMillis()
        var t: SshTunnel? = null
        return try {
            val tunnel = tunnelFor(machine).also { t = it; inflight = it }
            val c = NodeClient("http://127.0.0.1:${tunnel.open()}")
            // Сначала рукопожатие: оно отделяет «узел жив» от «не дозвонились».
            // Без этого молчание сети выдавалось бы за тишину на машине.
            val hello = c.hello()
            // Кольцо пустое — на машине не случалось вообще ничего. Спрашивать
            // события бессмысленно: узел прождал бы весь long-poll впустую.
            if (hello.buffered == 0L) return Probe(at = now)
            // Просим ровно то, что у узла ещё есть. `since = 0` здесь врал:
            // кольцо держит две тысячи событий, и на машине, где реально
            // работали, ответом всегда был `gap` с пустым списком — то есть
            // бодрое «тихо» вместо настоящей картины.
            val since = (hello.cursor - hello.buffered).coerceAtLeast(0)
            val page = c.events(since)
            if (page.gap) return Probe(error = "узел потерял начало ленты", at = now)
            val sessions = Reducer.apply(emptyMap(), page.events).values
            val tally = sessions.tally()
            Probe(
                waiting = tally.waiting,
                working = tally.working,
                done = tally.done,
                stuck = tally.stuck,
                total = sessions.size,
                at = now,
            )
        } catch (e: Exception) {
            // Первая строка: на карточке всё равно две строки, а инструкция
            // про JARVIS_NODE_TCP оборвалась бы на полуслове. Целиком её
            // человек увидит, когда зайдёт на машину.
            Probe(error = human(e, t).lineSequence().first(), at = now)
        } finally {
            runCatching { t?.close() }
            if (inflight === t) inflight = null
        }
    }

    private fun setProbe(id: String, probe: Probe) {
        _state.update { it.copy(probes = it.probes + (id to probe)) }
    }

    /* ================= чат ================= */

    /**
     * Открыть сессию по нажатию на уведомление.
     *
     * Связи в этот момент может ещё не быть — приложение только что запустили.
     * Поэтому сначала поднимаем машину, за которой следит служба, а чат
     * откроется, когда сессия появится в реестре.
     */
    fun openFromNotification(sessionId: String) {
        val watched = prefs.watchMachine ?: _state.value.machines.firstOrNull()?.id ?: return
        val current = currentMachineId()
        // Уведомление пришло от машины, за которой следит служба. Если сейчас
        // открыта ДРУГАЯ, показывать её чат под именем чужой машины нельзя:
        // клиент и реестр принадлежат текущей, и чат вышел бы пустым.
        if (current != null && current != watched) {
            pendingChat = sessionId
            open(watched)
            return
        }
        if (_state.value.link.online && registry.containsKey(sessionId)) {
            pendingChat = null // открыли сразу — отложенному открытию делать нечего
            openChat(watched, sessionId)
        } else {
            pendingChat = sessionId
            if (!_state.value.link.online) open(watched)
        }
    }

    fun openChat(machineId: String, sessionId: String) {
        chatTail?.cancel()
        chatTail = null
        feed = null
        _state.update {
            it.copy(
                screen = Screen.Chat(machineId, sessionId),
                chat = emptyList(),
                chatFrom = 0,
                artifacts = emptyList(),
                commands = emptyList(),
                workers = emptyList(),
                vitals = Vitals(),
                chatLoading = true,
                pane = null,
                notice = null,
            )
        }
        if (registry[sessionId]?.question != null) showPane(sessionId, "Спрашивает")
        resumeTail()
    }

    /**
     * Поднять дочитывание открытого чата, если оно не идёт.
     *
     * Одна точка входа на три случая: вход в чат, возврат из фона и появление
     * транскрипта у только что запущенной сессии — у неё пути ещё нет в реестре,
     * и раньше экран так и оставался на «Транскрипта пока нет» до перезахода.
     */
    private fun resumeTail() {
        if (!foreground) return
        val screen = _state.value.screen
        if (screen !is Screen.Chat) return
        // Именно «жив ли», а не «есть ли»: упавшая корутина оставляет ссылку на
        // себя, и по ней дочитывание выглядит идущим, хотя его нет. Чат тогда
        // замирает навсегда — до выхода и повторного входа.
        if (chatTail?.isActive == true) return
        val path = registry[screen.sessionId]?.transcript
        if (path == null) {
            _state.update { it.copy(chatLoading = false) }
            return
        }
        val f = feed?.takeIf { it.sessionId == screen.sessionId && it.path == path }
            ?: ChatFeed(screen.sessionId, path).also { feed = it }
        val job = viewModelScope.launch { tailChat(f) }
        chatTail = job
        job.invokeOnCompletion { if (chatTail === job) chatTail = null }
    }

    /**
     * Живой чат: дочитываем транскрипт, пока экран открыт.
     *
     * Раньше это был СНИМОК — транскрипт читался один раз при входе. Агент
     * отвечал, а на экране не менялось ничего, пока не выйдешь и не зайдёшь
     * снова. Список сессий при этом обновлялся, поэтому неподвижный чат
     * читался как «зависло».
     *
     * Дочитываем инкрементально, с последнего смещения: перечитывать хвост
     * целиком раз в несколько секунд — это мегабайты через ssh на телефоне.
     */
    private suspend fun tailChat(feed: ChatFeed) {
        var first = true
        while (currentCoroutineContext().isActive) {
            // Транскрипт сменился: агент пишет в НОВЫЙ файл после `--resume` и
            // после сжатия контекста. Старый при этом просто перестаёт расти —
            // с виду это неотличимо от «агент молчит», и лента замирает до
            // выхода из чата. Уходим, дочитывание поднимется уже по новому пути.
            val fresh = registry[feed.sessionId]?.transcript
            if (fresh != null && fresh != feed.path) return
            val c = client
            if (c == null) { delay(TAIL_IDLE_MS); continue }
            val chunk = try {
                withContext(Dispatchers.IO) {
                    if (!feed.started) {
                        // Первый заход — хвост файла: телефону нужен разговор,
                        // а не архив.
                        feed.startAt(c.file(feed.path, Long.MAX_VALUE)?.size ?: 0)
                    }
                    c.file(feed.path, feed.offset)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                delay(TAIL_IDLE_MS)
                continue
            }
            if (chunk == null) { // транскрипта ещё нет
                _state.update { it.copy(chatLoading = false) }
                delay(TAIL_IDLE_MS)
                continue
            }
            // Разбор и пересборка списков — не на главном потоке: это проход по
            // новым строкам плюс сортировки, а круг идёт каждые три секунды.
            //
            // Одна битая строка не имеет права уносить с собой всю ленту:
            // упавшая корутина не возобновляется, и человек видит застывший
            // чат без единого признака поломки.
            val snap = try {
                withContext(Dispatchers.Default) { feed.accept(chunk) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                delay(TAIL_IDLE_MS)
                continue
            }
            if (snap != null) {
                _state.update {
                    it.copy(
                        chat = snap.chat,
                        chatFrom = snap.chatFrom,
                        artifacts = snap.artifacts,
                        commands = snap.commands,
                        workers = snap.workers,
                        vitals = it.vitals.copy(
                            model = snap.model.takeIf { m -> m.isNotBlank() }
                                ?.let { m -> VitalsReader.friendly(m) }
                                ?: it.vitals.model,
                        ),
                        chatLoading = false,
                    )
                }
            }
            if (first) {
                _state.update { it.copy(chatLoading = false) }
                // Effort наружу не отдаётся нигде, кроме подвала самого агента.
                loadVitals(feed.sessionId)
                first = false
            }
            // Дочитали до конца — ждём; не дочитали (большой кусок) — сразу дальше.
            if (chunk.eof) delay(TAIL_IDLE_MS)
        }
    }

    /**
     * Прервать агента.
     *
     * Escape — то же, чем прерывают его в терминале. Без этого единственным
     * ответом на «делает не то» было ожидание: остановить работу с телефона
     * было нечем. Подтверждения не спрашиваем — прерывание обратимо, в отличие
     * от `/clear`.
     */
    fun interrupt(sessionId: String) {
        // Пану проверяем здесь: sendKey при её отсутствии молча ругается, а мы
        // рядом ставили бодрое «прервал» — человек получал две плашки разом.
        if (registry[sessionId]?.pane == null) return fail("Сессия не в tmux — прервать нечем")
        sendKey(sessionId, "Escape")
        _state.update { it.copy(notice = "Прервал — агент остановится на текущем шаге") }
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
     * Дочитать effort с экрана агента.
     *
     * Отдельным запросом, потому что взять его больше неоткуда: в хуках его
     * нет, в транскрипте нет, а настольный Jarvis помнит лишь то, что выставил
     * сам. Тихо: не вышло — просто не показываем, это не повод для ошибки.
     */
    private fun loadVitals(sessionId: String) {
        val paneId = registry[sessionId]?.pane ?: return
        val c = client ?: return
        viewModelScope.launch {
            val screen = withContext(Dispatchers.IO) {
                runCatching { c.screen(paneId).screen }.getOrDefault("")
            }
            if (screen.isBlank()) return@launch
            // За десять секунд запроса человек успевает открыть другой чат —
            // без этой проверки туда впишется модель предыдущего.
            val open = _state.value.screen
            if (open !is Screen.Chat || open.sessionId != sessionId) return@launch
            val fromScreen = VitalsReader.fromScreen(screen)
            val cur = _state.value.vitals
            _state.update { st -> st.copy(
                vitals = Vitals(
                    // Транскрипт про модель надёжнее подвала: там она в каждой
                    // записи, а подвал её показывает не всегда.
                    model = cur.model.ifBlank { VitalsReader.friendly(fromScreen.model) },
                    effort = fromScreen.effort,
                ),
            ) }
        }
    }

    /**
     * Снять экран паны, чтобы увидеть настоящие варианты ответа.
     *
     * Дёргается, когда человек открыл чат сессии с вопросом. Без этого кнопки
     * пришлось бы выдумывать — и они появлялись бы даже там, где агент просто
     * закончил работу.
     */
    fun showPane(sessionId: String, title: String) {
        val paneId = registry[sessionId]?.pane ?: return fail("Сессия не в tmux — экрана нет")
        val c = client ?: return
        _state.value = _state.value.copy(pane = PaneView(sessionId, title))
        refreshPane(sessionId, paneId, c)
    }

    /** Перечитать экран открытой паны. */
    fun refreshPane() {
        val cur = _state.value.pane ?: return
        val paneId = registry[cur.sessionId]?.pane ?: return
        val c = client ?: return
        refreshPane(cur.sessionId, paneId, c)
    }

    private fun refreshPane(sessionId: String, paneId: String, c: NodeClient) {
        // Лист паны закрыт — снимать экран не для кого. Проверяем ДО запроса:
        // бесплатных обращений к узлу на телефоне не бывает.
        if (_state.value.pane == null) return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { c.screen(paneId).screen }.getOrDefault("")
            }
            val cur = _state.value.pane ?: return@launch
            if (cur.sessionId != sessionId) return@launch // человек уже ушёл дальше
            _state.value = _state.value.copy(
                pane = cur.copy(screen = text.trimEnd(), picker = PickerReader.read(text), loading = false),
            )
        }
    }

    fun closePane() {
        _state.value = _state.value.copy(pane = null)
    }

    /**
     * Нажать клавишу в пане и показать, что из этого вышло.
     *
     * Ради последнего всё и делается: агент рисует TUI, и без перечитывания
     * экрана человек нажимает вслепую и не знает, сработало ли.
     */
    fun sendKey(sessionId: String, key: String) {
        val paneId = registry[sessionId]?.pane ?: return fail("Сессия не в tmux — нажимать некуда")
        val c = client ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { c.keys(paneId, listOf(KeyStep(key = key))) }
            }.onFailure { fail(human(it)) }
            delay(500) // дать TUI перерисоваться
            refreshPane(sessionId, paneId, c)
        }
    }

    /** Ответ на вопрос цифрой — частный случай нажатия клавиши. */
    fun answer(sessionId: String, option: Int) = sendKey(sessionId, option.toString())

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
            val sent = withContext(Dispatchers.IO) { runCatching { c.control(pane, text) } }
            sent.onFailure { fail(human(it)); return@launch }
            // Команда почти всегда что-то РИСУЕТ: пикер модели, сводку расхода,
            // подтверждение. Отправить и не показать результат — оставить
            // человека гадать, сработало ли; ради этого экран и открывается.
            delay(700)
            showPane(sessionId, text)
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
        // Человек ушёл сам — отложенное открытие из уведомления отменяется.
        // Иначе оно выстрелит через полчаса и утащит его обратно.
        pendingChat = null
        // Открытый артефакт закрывается первым: он лежит поверх экрана, и
        // «назад» из него означает вернуться к тому же экрану, а не глубже.
        // Дочитывание при этом не трогаем — чат остался открытым под ним.
        if (_state.value.artifact != null) {
            closeArtifact()
            return
        }
        // Ушли из чата — дочитывать нечего: экран закрыт, а трафик платный.
        if (_state.value.screen is Screen.Chat) {
            chatTail?.cancel()
            chatTail = null
            feed = null
        }
        _state.value = when (_state.value.screen) {
            is Screen.Chat -> _state.value.copy(screen = Screen.Sessions(currentMachineId().orEmpty()), notice = null)
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
    private fun human(e: Throwable, over: SshTunnel? = null): String {
        val raw = e.message.orEmpty()
        // Туннель передаётся явно: у обхода машин он свой, а поле класса в этот
        // момент либо пусто, либо принадлежит совсем другой машине.
        val why = (over ?: tunnel)?.forwardError?.takeIf { it.isNotBlank() }
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
        /** Пол опроса: узел уже держал запрос до 25 с, спешить некуда. */
        const val POLL_FLOOR_MS = 800L
        /** Сколько хвоста артефакта тянуть: экран телефона всё равно меньше. */
        const val ARTIFACT_BYTES = 128L * 1024
        /** Пауза между дочитываниями открытого чата. */
        const val TAIL_IDLE_MS = 3_000L
    }
}
