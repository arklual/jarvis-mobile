package dev.jarvis.mobile

import dev.jarvis.mobile.model.Block
import dev.jarvis.mobile.model.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `блоки кода и разметка внутри них`() {
        val md = """
            Вот команда:

            ```bash
            npm test -- **не разметка**
            ```

            Готово.
        """.trimIndent()
        val blocks = Markdown.parse(md)
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertEquals("bash", code.lang)
        // внутри блока кода звёздочки — часть кода, а не жирный шрифт
        assertTrue(code.text.contains("**не разметка**"))
    }

    @Test
    fun `заголовки, списки и цитаты`() {
        val blocks = Markdown.parse("## Итог\n- первый\n- второй\n> цитата\n")
        assertEquals(1, blocks.filterIsInstance<Block.Heading>().size)
        assertEquals(2, blocks.count { it is Block.Item })
        assertEquals(1, blocks.count { it is Block.Quote })
        assertEquals(2, (blocks.first() as Block.Heading).level)
    }

    @Test
    fun `жирный, курсив и код в строке`() {
        val spans = Markdown.spans("тут **жирно**, тут *курсив* и `код`")
        assertTrue(spans.any { it.bold && it.text == "жирно" })
        assertTrue(spans.any { it.italic && it.text == "курсив" })
        assertTrue(spans.any { it.code && it.text == "код" })
    }

    @Test
    fun `звёздочки без пары остаются текстом`() {
        // «2 * 3 = 6» не должно превращаться в курсив до конца строки
        val spans = Markdown.spans("2 * 3 = 6")
        assertEquals(1, spans.size)
        assertEquals("2 * 3 = 6", spans[0].text)
        assertTrue(Markdown.spans("****").none { it.bold })
    }

    @Test
    fun `строки абзаца склеиваются, а пустая строка его закрывает`() {
        val blocks = Markdown.parse("первая\nвторая\n\nдругой абзац")
        val paras = blocks.filterIsInstance<Block.Para>()
        assertEquals(2, paras.size)
        assertEquals("первая вторая", paras[0].spans.joinToString("") { it.text })
    }
}
