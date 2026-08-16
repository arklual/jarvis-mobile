package dev.jarvis.mobile

import dev.jarvis.mobile.model.Session
import dev.jarvis.mobile.model.dropDeadPanes
import dev.jarvis.mobile.transport.Hello
import dev.jarvis.mobile.transport.replayFrom
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * С какого места читать ленту, чтобы увидеть ВСЕ открытые сессии.
 *
 * Это тот самый баг «телефон показывает не все сессии»: опрос начинался с нуля,
 * а кольцо узла держит две тысячи событий. На машине, где работали, узел на
 * `since = 0` отвечает `gap` с пустым списком — приложение считало, что сессий
 * нет, и наполняло список только тем, что случилось при открытом экране.
 * Сессия, которая молча ждёт ответа, не появлялась никогда.
 */
class ReplayTest {

    @Test
    fun `просим ровно то, что узел ещё помнит`() {
        // Кольцо вытеснило начало: курсор 5000, в буфере последние 2000.
        assertEquals(3000, Hello(cursor = 5000, buffered = 2000).replayFrom())
    }

    @Test
    fun `на молодом узле читаем с самого начала`() {
        // Ничего не вытеснялось — начало ленты и есть ноль, а не отрицательное
        // число: узел на такое ответил бы отказом.
        assertEquals(0, Hello(cursor = 120, buffered = 120).replayFrom())
        assertEquals(0, Hello(cursor = 0, buffered = 0).replayFrom())
    }

    @Test
    fun `буфер больше курсора не уводит запрос в минус`() {
        // Узел перезапускали: курсор пошёл заново, а буфер отвечает о прошлом.
        // Отрицательный `since` — это 400 от узла и пустой экран.
        assertEquals(0, Hello(cursor = 10, buffered = 900).replayFrom())
    }

    @Test
    fun `сессия без событий в буфере остаётся, пока жива её пана`() {
        // Ровно тот случай, ради которого лезут в телефон: агент спросил и
        // молчит, его событие уехало из кольца. Пана жива — значит и сессия.
        val reg = mapOf(
            "waiting" to Session(id = "waiting", pane = "%3"),
            "closed" to Session(id = "closed", pane = "%9"),
            "noTmux" to Session(id = "noTmux", pane = null),
        )
        val left = reg.dropDeadPanes(setOf("%3"))
        assertEquals(setOf("waiting", "noTmux"), left.keys)
    }

    @Test
    fun `пустой список пан ничего не вычёркивает`() {
        // tmux на машине не поднят или узел не смог его опросить. Считать это
        // «все сессии умерли» — значит очистить экран на ровном месте.
        val reg = mapOf("a" to Session(id = "a", pane = "%1"))
        assertEquals(reg.keys, reg.dropDeadPanes(emptySet()).keys)
    }
}
