package dev.jarvis.mobile.transport

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.common.SecurityUtils

/**
 * Как телефон попадает к узлу.
 *
 * Узел слушает unix-сокет и (если включено) порт на петле ТОЙ машины. Наружу он
 * не открыт ничем — значит нужен ssh-туннель. Форвард на unix-сокет здесь не
 * годится: это расширение OpenSSH (`direct-streamlocal`), которого sshj не
 * умеет. Поэтому на узле включается `JARVIS_NODE_TCP=127.0.0.1:<порт>`, а сюда
 * пробрасывается обычный TCP.
 *
 * Отпечаток хоста проверяется всерьёз: первый раз запоминаем (человек добавляет
 * машину осознанно), при СМЕНЕ — отказ. Именно смена, а не первое знакомство,
 * является признаком подмены.
 */
class SshTunnel(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val auth: Auth,
    private val remotePort: Int,
    private val knownHosts: KnownHosts,
) : AutoCloseable {

    sealed interface Auth {
        data class Password(val password: String) : Auth
        /** Приватный ключ в PEM/OpenSSH-формате, при необходимости с пассфразой. */
        data class PrivateKey(val pem: String, val passphrase: String?) : Auth
    }

    private var client: SSHClient? = null
    private var forwarder: LocalPortForwarder? = null
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    /** Локальный порт, на котором доступен узел. 0 — туннель не поднят. */
    @Volatile
    var localPort: Int = 0
        private set

    /**
     * Поднять туннель и вернуть, когда он начал принимать. Ждём именно события,
     * а не «немного времени»: sshj открывает слушатель после аутентификации, и
     * гадать о таймингах здесь — верный способ получить плавающие отказы.
     */
    @Throws(IOException::class)
    fun open(timeoutSeconds: Long = 20): Int {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(verifier())
        ssh.connectTimeout = 15_000
        ssh.timeout = 20_000
        ssh.connect(host, port)
        when (auth) {
            is Auth.Password -> ssh.authPassword(user, auth.password)
            is Auth.PrivateKey -> ssh.authPublickey(user, keyProvider(ssh, auth))
        }
        client = ssh

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", 0)) // порт выбирает система
        server = socket
        localPort = socket.localPort

        val params = Parameters("127.0.0.1", localPort, "127.0.0.1", remotePort)
        val fwd = ssh.newLocalPortForwarder(params, socket)
        forwarder = fwd

        val ready = CountDownLatch(1)
        val t = Thread({
            try {
                ready.countDown() // listen() блокирует навсегда — сигналим до него
                fwd.listen()
            } catch (_: IOException) {
                // закрытие туннеля приходит сюда же — это не ошибка
            }
        }, "jarvis-ssh-$host")
        t.isDaemon = true
        thread = t
        t.start()
        if (!ready.await(timeoutSeconds, TimeUnit.SECONDS)) {
            close()
            throw IOException("туннель не начал принимать за $timeoutSeconds с")
        }
        return localPort
    }

    private fun keyProvider(ssh: SSHClient, key: Auth.PrivateKey): KeyProvider {
        val finder = key.passphrase
            ?.takeIf { it.isNotEmpty() }
            ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        return ssh.loadKeys(key.pem, null, finder)
    }

    // Не лямбда: у HostKeyVerifier два метода, и SAM-преобразование к нему
    // неприменимо. findExistingAlgorithms пустой — пусть sshj предлагает свои.
    private fun verifier(): HostKeyVerifier = object : HostKeyVerifier {
        override fun verify(hostname: String, p: Int, key: PublicKey): Boolean =
            knownHosts.verify("$hostname:$p", SecurityUtils.getFingerprint(key))

        override fun findExistingAlgorithms(hostname: String, p: Int): List<String> = emptyList()
    }

    override fun close() {
        localPort = 0
        runCatching { forwarder = null; server?.close() }
        runCatching { client?.disconnect() }
        client = null
        server = null
        thread = null
    }
}

/**
 * Запомненные отпечатки хостов. Первое подключение принимаем (машину добавляет
 * человек, а не мы), смену — нет.
 */
interface KnownHosts {
    fun verify(hostPort: String, fingerprint: String): Boolean
}

/** Проверка отпечатка поверх любого хранилища «ключ → значение». */
class MapKnownHosts(private val store: MutableMap<String, String>) : KnownHosts {
    override fun verify(hostPort: String, fingerprint: String): Boolean {
        val known = store[hostPort]
        if (known == null) {
            store[hostPort] = fingerprint
            return true
        }
        return known == fingerprint
    }
}
