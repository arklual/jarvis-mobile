package dev.jarvis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.model.Block
import dev.jarvis.mobile.model.Markdown
import dev.jarvis.mobile.model.Span

/**
 * Ответ агента как markdown, а не как простыня текста.
 *
 * Агент пишет разметкой всегда: списки, заголовки, блоки кода. Показывая её
 * сырой, мы заставляем человека читать `**` и ``` глазами — и терять структуру,
 * ради которой она и написана.
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(if (block is Block.Code) 6.dp else 3.dp))
            when (block) {
                is Block.Para -> Text(block.spans.annotated(), style = MaterialTheme.typography.bodyMedium, color = color)
                is Block.Heading -> Text(
                    block.spans.annotated(),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                    color = color,
                )
                is Block.Item -> Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width((block.depth * 12).dp))
                    Text(
                        block.marker,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(block.spans.annotated(), style = MaterialTheme.typography.bodyMedium, color = color)
                }
                is Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    // Полоска слева, а не кавычки: цитату видно боковым зрением
                    Spacer(
                        Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        block.spans.annotated(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is Block.Code -> CodeBlock(block)
                Block.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * Код прокручивается вбок и не переносится.
 *
 * Перенос кода по ширине телефона делает его нечитаемым: ломается отступ, и
 * строка команды разъезжается на три. Лучше прокрутка, чем каша.
 */
@Composable
private fun CodeBlock(block: Block.Code) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(PaddingValues(horizontal = 10.dp, vertical = 8.dp))
    ) {
        if (block.lang.isNotBlank()) {
            Text(
                block.lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            block.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

/** Куски строки → размеченная строка Compose. */
@Composable
private fun List<Span>.annotated(): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    return buildAnnotatedString {
        for (s in this@annotated) {
            when {
                s.code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
                ) { append(s.text) }
                s.bold && s.italic -> withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
                ) { append(s.text) }
                s.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.text) }
                s.italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                else -> append(s.text)
            }
        }
    }
}
