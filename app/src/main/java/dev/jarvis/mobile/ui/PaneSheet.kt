package dev.jarvis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.PaneView

/**
 * Живой экран сессии — то, что человек увидел бы, подключившись к ней.
 *
 * Единственное честное окно в происходящее: агент рисует TUI, а хуки о нём не
 * рассказывают. Через него отвечают на вопрос, выбирают модель после `/model` и
 * просто смотрят, что там творится.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneSheet(
    view: PaneView,
    onKey: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PagePad)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(view.title)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Обновить") }
            }
            Spacer(Modifier.height(6.dp))

            if (view.loading && view.screen.isBlank()) {
                Text(
                    "Смотрю на экран агента…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Экран моноширинно и без переносов: TUI выравнен пробелами, и
                // перенос по ширине телефона превращает рамки в кашу.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        view.screen.ifBlank { "(пусто)" },
                        style = MonoTiny,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }

            if (!view.picker.empty) {
                Spacer(Modifier.height(10.dp))
                SectionTitle("Выбрать")
                view.picker.options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onKey(option.number.toString()) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { onKey(option.number.toString()) }) {
                            Text("${option.number}")
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (option.selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionTitle("Клавиши")
            Spacer(Modifier.height(4.dp))
            // Стрелки и Enter нужны там, где пикер не распознался: у агента
            // бывают экраны, где выбирают перемещением, а не цифрой.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Up" to "↑", "Down" to "↓", "Enter" to "Enter", "Escape" to "Esc").forEach {
                    OutlinedButton(onClick = { onKey(it.first) }) { Text(it.second) }
                }
            }
        }
    }
}
