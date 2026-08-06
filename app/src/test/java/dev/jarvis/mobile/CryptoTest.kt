package dev.jarvis.mobile

import dev.jarvis.mobile.transport.Crypto
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    /**
     * Сторож зависимости, а не проверка Android.
     *
     * На телефоне sshj падал с «no such algorithm: X25519 for provided BC»:
     * платформенный BouncyCastle урезан. Мы возим полный с собой — и если его
     * когда-нибудь выкинут из зависимостей, тест это заметит здесь, а не на
     * рукопожатии с чужим сервером.
     */
    @Test
    fun `в поставке есть X25519`() {
        Crypto.install()
        assertTrue("X25519 обязан быть у нашего BouncyCastle", Crypto.hasX25519())
    }
}
