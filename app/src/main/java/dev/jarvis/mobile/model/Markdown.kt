package dev.jarvis.mobile.model

/**
 * Разбор markdown ответов агента — ровно того подмножества, которым он реально
 * пользуется: заголовки, списки, блоки кода, жирный, курсив и `код` в строке.
 *
 * Свой, а не библиотека, по двум причинам. Во-первых, полный markdown агенту не
 * нужен: таблицы и сноски в ответах не встречаются, а разбирать их — тащить
 * тысячи строк чужого кода в приложение, которое целиком меньше. Во-вторых, всё
 * это невозможно проверить на устройстве до релиза, и предсказуемые сто строк
 * здесь честнее непредсказуемой зависимости.
 *
 * Разбор чисто текстовый: превращает markdown в список блоков, а как их
 * рисовать — решает Compose.
 */
sealed interface Block {
    /** Абзац с уже размеченными кусками. */
    data class Para(val spans: List<Span>) : Block

    /** Заголовок: `level` — сколько решёток, 1..3 (глубже агент не пишет). */
    data class Heading(val level: Int, val spans: List<Span>) : Block

    /** Пункт списка: `marker` — «•» или «1.», `spans` — сам текст. */
    data class Item(val marker: String, val spans: List<Span>, val depth: Int) : Block

    /** Блок кода: `lang` бывает пустым. */
    data class Code(val lang: String, val text: String) : Block

    /** Цитата — агент так оформляет вывод команд и чужой текст. */
    data class Quote(val spans: List<Span>) : Block

    data object Rule : Block
}

/** Кусок текста с одним начертанием. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

object Markdown {

    private val FENCE = Regex("""^\s*```\s*(\S*)\s*$""")
    private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
    private val RULE = Regex("""^\s{0,3}([-*_])\s*(\1\s*){2,}$""")

    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        var fence: StringBuilder? = null
        var lang = ""

        fun flushPara() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) out.add(Block.Para(spans(t)))
            para.setLength(0)
        }

        for (line in text.lines()) {
            val f = FENCE.find(line)
            if (fence != null) {
                // Внутри блока кода markdown не действует: там и решётки, и
                // звёздочки — часть кода, а не разметка.
                if (f != null) {
                    out.add(Block.Code(lang, fence.toString().trimEnd('\n')))
                    fence = null
                    lang = ""
                } else {
                    fence.append(line).append('\n')
                }
                continue
            }
            if (f != null) {
                flushPara()
                fence = StringBuilder()
                lang = f.groupValues[1]
                continue
            }
            when {
                line.isBlank() -> flushPara()
                RULE.matches(line) -> { flushPara(); out.add(Block.Rule) }
                HEADING.matches(line) -> {
                    flushPara()
                    val m = HEADING.find(line)!!
                    out.add(Block.Heading(m.groupValues[1].length.coerceAtMost(3), spans(m.groupValues[2])))
                }
                QUOTE.matches(line) -> {
                    flushPara()
                    out.add(Block.Quote(spans(QUOTE.find(line)!!.groupValues[1])))
                }
                BULLET.matches(line) -> {
                    flushPara()
                    val m = BULLET.find(line)!!
                    out.add(Block.Item("•", spans(m.groupValues[2]), depth(m.groupValues[1])))
                }
                NUMBERED.matches(line) -> {
                    flushPara()
                    val m = NUMBERED.find(line)!!
                    out.add(Block.Item("${m.groupValues[2]}.", spans(m.groupValues[3]), depth(m.groupValues[1])))
                }
                // Мягкий перенос: markdown склеивает соседние строки абзаца,
                // и агент на это рассчитывает — иначе текст рвался бы по
                // случайным местам ширины его терминала.
                else -> para.append(if (para.isEmpty()) "" else " ").append(line.trim())
            }
        }
        if (fence != null) out.add(Block.Code(lang, fence.toString().trimEnd('\n')))
        flushPara()
        return out
    }

    private fun depth(indent: String): Int = (indent.length / 2).coerceIn(0, 3)

    /**
     * Разметка внутри строки. Порядок важен: `код` забирает своё первым, потому
     * что внутри него звёздочки не разметка.
     */
    fun spans(line: String): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(Span(buf.toString()))
                buf.setLength(0)
            }
        }

        while (i < line.length) {
            val rest = line.substring(i)
            val code = takeDelimited(rest, "`")
            if (code != null) {
                flush(); out.add(Span(code.first, code = true)); i += code.second; continue
            }
            val bold = takeDelimited(rest, "**")
            if (bold != null) {
                flush(); out.add(Span(bold.first, bold = true)); i += bold.second; continue
            }
            val italic = takeDelimited(rest, "*") ?: takeDelimited(rest, "_")
            if (italic != null) {
                flush(); out.add(Span(italic.first, italic = true)); i += italic.second; continue
            }
            buf.append(line[i]); i++
        }
        flush()
        return if (out.isEmpty()) listOf(Span(line)) else out
    }

    /**
     * Кусок между парой одинаковых ограничителей → (содержимое, сколько съели).
     * Пустое содержимое не считаем разметкой: `****` в тексте — это звёздочки,
     * а не жирная пустота.
     */
    private fun takeDelimited(s: String, mark: String): Pair<String, Int>? {
        if (!s.startsWith(mark)) return null
        val end = s.indexOf(mark, mark.length)
        if (end <= mark.length) return null
        return s.substring(mark.length, end) to (end + mark.length)
    }
}
