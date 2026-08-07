package dev.jarvis.mobile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.jarvis.mobile.R
import dev.jarvis.mobile.data.AuthKind
import dev.jarvis.mobile.data.Machines
import dev.jarvis.mobile.data.Prefs
import dev.jarvis.mobile.data.SecretStore
import dev.jarvis.mobile.model.Reducer
import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.Status
import dev.jarvis.mobile.model.title
import dev.jarvis.mobile.transport.MapKnownHosts
import dev.jarvis.mobile.transport.NodeClient
import dev.jarvis.mobile.transport.SshTunnel
import dev.jarvis.mobile.transport.backoffSeconds
import dev.jarvis.mobile.transport.isTransient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Фоновая связь с машиной, пока приложение свёрнуто.
 *
 * Иначе уведомлений не бывает вовсе: Android останавливает корутины свёрнутого
 * приложения, и узнать о завершении работы неоткуда. Push-канала у этой схемы
 * нет и быть не может — узел стоит на чужой машине и наружу не смотрит, так что
 * единственный способ узнать новость — держать соединение самим.
 *
 * Цена честная и видимая: постоянное уведомление, которое система показывает и
 * спрятать не даёт. Поэтому служба живёт только по явному тумблеру в настройках.
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var tunnel: SshTunnel? = null
    /** Живой клиент и реестр — чтобы ответить из шторки, не поднимая второй туннель. */
    @Volatile
    private var live: NodeClient? = null
    @Volatile
    private var known: Map<String, Session> = emptyMap()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        startForeground()
        val reply = intent?.getStringExtra(EXTRA_REPLY_SESSION)
        reply?.let { sid ->
            val text = intent.getStringExtra(EXTRA_REPLY_TEXT).orEmpty()
            if (text.isNotBlank()) scope.launch { deliver(sid, text); stopIfOnlyForReply() }
        }
        // Слежение поднимаем, только если его просили. Ответ по старой карточке
        // при выключенном тумблере не должен молча возвращать фоновую службу:
        // человек её выключил осознанно.
        if (job == null && Prefs(this).keepAlive) job = scope.launch { watch() }
        else if (job == null && reply != null) job = scope.launch { watchOnce() }
        // START_STICKY: система вправе прибить службу под нехватку памяти, и
        // молча перестать следить — ровно то, чего человек не ждёт, включив
        // тумблер.
        return START_STICKY
    }

    /**
     * Система решила, что служба работает слишком долго.
     *
     * Молчать здесь нельзя: по контракту API 35 не остановившаяся служба
     * получает ANR. Останавливаемся сами — уведомления пропадут, но приложение
     * останется живым, а связь поднимется при следующем открытии.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { tunnel?.close() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground() {
        val n = NotificationCompat.Builder(this, Notifier.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Слежу за агентами")
            .setContentText("Держу связь с машиной, чтобы сообщить о завершении")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        // На API 31+ система вправе отказать в старте службы переднего плана.
        // Необработанное исключение здесь роняет процесс целиком — а отказ
        // означает лишь, что уведомления пока не будет.
        runCatching { ServiceCompat.startForeground(this, NOTE_ID, n, type) }
    }

    /**
     * Разовое подключение ради доставки ответа: слежение выключено, но ответить
     * человек попросил. Соединение живёт ровно до конца доставки.
     */
    private suspend fun watchOnce() {
        val secrets = SecretStore(this)
        val machine = Prefs(this).watchMachine?.let { id ->
            Machines.decode(secrets.machinesRaw).byId(id)
        } ?: return
        runCatching {
            val client = connect(machine, secrets)
            live = client
            // Реестр нужен ради паны: без него отвечать некуда. Просим НЕДАВНЮЮ
            // историю, а не «с текущего места»: с текущего лента пуста, и
            // запрос просто провисел бы весь long-poll впустую.
            val from = (client.hello().cursor - RECENT_EVENTS).coerceAtLeast(0)
            known = Reducer.apply(emptyMap(), client.events(from).events)
        }
    }

    /** Разовая доставка закончена — держать соединение и уведомление незачем. */
    private fun stopIfOnlyForReply() {
        if (!Prefs(this).keepAlive) {
            runCatching { tunnel?.close() }
            stopSelf()
        }
    }

    /** Тот же супервизор, что и в приложении: подключился → слушай → упало → снова. */
    private suspend fun watch() {
        val prefs = Prefs(this)
        val secrets = SecretStore(this)
        val machine = prefs.watchMachine?.let { id ->
            Machines.decode(secrets.machinesRaw).byId(id)
        } ?: run { stopSelf(); return }

        var registry: Map<String, Session> = emptyMap()
        // -1 значит «ещё не знаем, где лента»: на первом подключении спросим
        // это у узла. Начать с нуля — значит прочитать весь его буфер и
        // разослать уведомления о работе, которая закончилась вчера.
        var cursor = -1L
        var attempt = 0
        while (scope.isActive) {
            val client = try {
                connect(machine, secrets)
            } catch (e: Exception) {
                if (!isTransient(e.message.orEmpty())) { stopSelf(); return }
                attempt++
                delay(backoffSeconds(attempt) * 1000L)
                continue
            }
            live = client
            attempt = 0
            // Собрать реестр заново, не объявляя об этом. Взводится разрывом
            // ленты; при первом запуске не нужен — курсор и так ставится на
            // «что произойдёт дальше».
            var reseed = false
            if (cursor < 0) {
                // Следим за тем, что произойдёт ДАЛЬШЕ, а не за историей.
                cursor = runCatching { client.hello().cursor }.getOrDefault(0)
            }
            try {
                while (scope.isActive) {
                    val page = client.events(cursor)
                    if (page.gap) {
                        // Лента порвалась: старый реестр держит мёртвые паны, и
                        // ответ ушёл бы в никуда.
                        registry = emptyMap()
                        known = emptyMap()
                        // Следующую порцию собираем молча. Сравнивать её не с
                        // чем, и каждая сессия выглядела бы только что
                        // изменившейся — телефон разом вывалил бы горсть
                        // уведомлений про работу, законченную час назад.
                        reseed = true
                    }
                    if (page.events.isNotEmpty()) {
                        val next = Reducer.apply(registry, page.events)
                        if (reseed) reseed = false else notifyChanges(prefs, registry, next)
                        registry = next
                        known = next
                    }
                    cursor = page.cursor
                    if (page.events.isEmpty()) delay(1_000)
                }
            } catch (e: Exception) {
                runCatching { tunnel?.close() }
                tunnel = null
                live = null
                known = emptyMap()
                if (!isTransient(e.message.orEmpty())) { stopSelf(); return }
                attempt++
                delay(backoffSeconds(attempt) * 1000L)
            }
        }
    }

    private fun connect(machine: dev.jarvis.mobile.data.Machine, secrets: SecretStore): NodeClient {
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
        return NodeClient("http://127.0.0.1:$local")
    }

    /**
     * Отправить ответ, введённый в шторке.
     *
     * Пана берётся из того же реестра, что ведёт слежение: другого источника у
     * службы нет, и он же гарантирует, что отвечаем в живую сессию.
     */
    private suspend fun deliver(sessionId: String, text: String) {
        // Ждём готовности, а не сдаёмся сразу. Самый вероятный путь такой:
        // службу подняли этим же нажатием, туннель ещё поднимается, реестра
        // ещё нет. Мгновенное «не ушло» стирало бы набранный текст ровно там,
        // где всё на самом деле в порядке — надо лишь подождать секунды.
        val deadline = System.currentTimeMillis() + WAIT_READY_MS
        var pane: String? = null
        var client: NodeClient? = null
        while (System.currentTimeMillis() < deadline) {
            pane = known[sessionId]?.pane
            client = live
            if (pane != null && client != null) break
            delay(300)
        }
        val ok = if (pane == null || client == null) {
            false
        } else {
            runCatching { client.reply(pane, text) }.isSuccess
        }
        // Текст не теряем: при неудаче он остаётся в карточке, и по нажатию
        // открывается нужный чат, где его можно отправить руками.
        Notifier.replied(this, sessionId, text, ok)
    }

    /**
     * Уведомляем на ПЕРЕХОДАХ, а не на состоянии.
     *
     * Иначе каждая страница событий заново сообщала бы «агент закончил» про то
     * же самое — а это ровно тот вид уведомлений, после которого их выключают.
     */
    private fun notifyChanges(prefs: Prefs, before: Map<String, Session>, after: Map<String, Session>) {
        for ((id, now) in after) {
            val was = before[id]?.status
            if (was == now.status) continue
            when (now.status) {
                Status.DONE -> if (prefs.notifyDone) {
                    Notifier.alert(this, id, "${now.title()} — закончила", now.detail.ifBlank { "работа завершена" })
                }
                // Ответ предлагаем только там, где его ждут: у «закончила»
                // поле ввода было бы приглашением написать в пустоту.
                Status.WAITING -> if (prefs.notifyWaiting) {
                    Notifier.alert(
                        this,
                        id,
                        "${now.title()} — спрашивает",
                        now.question ?: "ждёт ответа",
                        canReply = now.pane != null,
                    )
                }
                // Встала сама: работа не сделана и не продолжится, пока не
                // вмешаются. Это едва ли не важнее «закончила» — там просто
                // ждут взгляда, а тут всё стоит.
                Status.LIMIT, Status.FAILED -> if (prefs.notifyStuck) {
                    Notifier.alert(
                        this,
                        id,
                        "${now.title()} — ${if (now.status == Status.LIMIT) "лимит" else "сорвалась"}",
                        now.detail.ifBlank { "работа остановилась" },
                    )
                }
                else -> Unit
            }
        }
    }

    companion object {
        private const val NOTE_ID = 42
        /** Сколько ждать готовности связи, прежде чем признать ответ недоставленным. */
        private const val WAIT_READY_MS = 12_000L
        /** Сколько событий назад отмотать, чтобы собрать реестр живых сессий. */
        private const val RECENT_EVENTS = 500L
        const val EXTRA_REPLY_SESSION = "replySession"
        const val EXTRA_REPLY_TEXT = "replyText"

        /** Передать службе ответ из шторки: соединение уже держит она. */
        fun sendReply(context: Context, sessionId: String, text: String) {
            val i = Intent(context, WatchService::class.java)
                .putExtra(EXTRA_REPLY_SESSION, sessionId)
                .putExtra(EXTRA_REPLY_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun start(context: Context) {
            val i = Intent(context, WatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
