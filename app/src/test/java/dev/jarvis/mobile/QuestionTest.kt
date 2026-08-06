package dev.jarvis.mobile

import dev.jarvis.mobile.model.PickerReader
import dev.jarvis.mobile.model.Reducer
import dev.jarvis.mobile.model.Status
import dev.jarvis.mobile.transport.Recorded
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun event(body: String) = Recorded(envelope = json.parseToJsonElement(body) as JsonObject)

    /**
     * Тот самый баг: агент закончил работу, а приложение показывало карточку
     * «спрашивает» с четырьмя кнопками, за которыми ничего не стоит.
     */
    @Test
    fun `«ждёт твоего ввода» — это конец работы, а не вопрос`() {
        val reg = Reducer.apply(emptyMap(), listOf(
            event("""{"event":"prompt","payload":{"session_id":"a","prompt":"сделай"}}"""),
            event("""{"event":"notification","payload":{"session_id":"a","message":"Claude is waiting for your input"}}"""),
        ))
        assertEquals(Status.DONE, reg["a"]!!.status)
        assertNull(reg["a"]!!.question)
    }

    @Test
    fun `запрос разрешения — настоящий вопрос`() {
        val reg = Reducer.apply(emptyMap(), listOf(
            event("""{"event":"notification","payload":{"session_id":"a","notification_type":"permission_prompt","message":"Claude needs your permission to use Bash"}}"""),
        ))
        assertEquals(Status.WAITING, reg["a"]!!.status)
        assertEquals("Claude needs your permission to use Bash", reg["a"]!!.question)
    }

    @Test
    fun `тип уведомления важнее текста`() {
        // idle_prompt настольная версия считает требующим внимания, но текст у
        // него ровно тот же «waiting for your input» — доверяем типу
        val reg = Reducer.apply(emptyMap(), listOf(
            event("""{"event":"notification","payload":{"session_id":"a","notification_type":"other","message":"что-то ещё"}}"""),
        ))
        // в JUnit сообщение идёт ПЕРВЫМ аргументом
        assertNull("неизвестный тип вопросом не считаем", reg["a"]!!.question)
    }

    @Test
    fun `варианты читаются с экрана, а не выдумываются`() {
        val screen = """
            Quick safety check: Is this a project you created or one you trust?
            ❯ 1. Yes, I trust this folder
              2. No, exit
            Enter to confirm · Esc to cancel
        """.trimIndent()
        val p = PickerReader.read(screen)
        assertEquals(2, p.options.size)
        assertEquals("Yes, I trust this folder", p.options[0].label)
        assertTrue(p.options[0].selected)
        assertTrue(p.confirm)
    }

    @Test
    fun `нумерация из вывода команды пикером не считается`() {
        // список файлов с номерами не должен превращаться в кнопки: нажатая
        // цифра уехала бы в диалог текстом
        val screen = "  1. первый\n  3. третий\n  7. седьмой\n"
        assertTrue(PickerReader.read(screen).empty)
        assertTrue(PickerReader.read("").empty)
    }
}
