package dev.jarvis.mobile

import dev.jarvis.mobile.transport.backoffSeconds
import dev.jarvis.mobile.transport.isTransient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {

    @Test
    fun `пауза растёт и упирается в потолок`() {
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30), (0..6).map { backoffSeconds(it) })
    }

    @Test
    fun `повторяем обрыв, но не отказ в доступе`() {
        // это лечится повтором: телефон вышел из сети, экран заблокировали
        assertTrue(isTransient("connection reset"))
        assertTrue(isTransient("timeout"))
        assertTrue(isTransient("Network is unreachable"))
        // а это повтором не лечится — долбиться по кругу значит выглядеть
        // занятым, ничего не делая
        assertFalse(isTransient("Auth fail"))
        assertFalse(isTransient("Permission denied (publickey)"))
        assertFalse(isTransient("host key verification failed"))
        assertFalse(isTransient("channel 2: open failed: administratively prohibited"))
    }
}
