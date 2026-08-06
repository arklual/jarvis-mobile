package dev.jarvis.mobile.transport

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Подмена криптопровайдера — без неё sshj на Android не работает вовсе.
 *
 * В Android под именем «BC» зарегистрирован СВОЙ BouncyCastle, урезанный и
 * перепакованный (`com.android.org.bouncycastle`). В нём нет X25519, а именно
 * его хочет современный sshd: `curve25519-sha256` — обмен ключами по умолчанию
 * начиная с OpenSSH 6.5. Отсюда отказ «no such algorithm: X25519 for provided BC»
 * ещё на рукопожатии, до всякой аутентификации.
 *
 * Лечится заменой провайдера: снимаем платформенный «BC» и ставим настоящий,
 * который приезжает с приложением. Конфликта классов нет — платформенный лежит
 * в своём пространстве имён, так что мы отбираем только ИМЯ провайдера.
 *
 * Делать это надо до создания `SSHClient`: он на старте перебирает доступные
 * алгоритмы, и провайдер, подменённый позже, ему уже не поможет.
 */
object Crypto {
    @Volatile
    private var done = false

    @Synchronized
    fun install() {
        if (done) return
        // insertProviderAt(1), а не addProvider: провайдеры перебираются по
        // порядку, и настоящий BC должен опрашиваться раньше платформенного.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        SecurityUtils.setRegisterBouncyCastle(true)
        SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
        done = true
    }

    /** Есть ли у провайдера X25519 — то самое, чего не хватало. */
    fun hasX25519(): Boolean = runCatching {
        java.security.KeyFactory.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME)
    }.isSuccess
}
