package dev.jarvis.mobile.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.model.Status
import dev.jarvis.mobile.transport.Link

/*
 * Кирпичики «Клевера»: бумажная карточка, монохромные метки состояний и живой
 * индикатор работы. Всё, что встречается больше чем на одном экране, живёт
 * здесь — иначе оттенки и отступы расползаются по экранам и система рассыпается.
 */

/** Поля страницы. Одно число на всё приложение — чтобы колонки совпадали. */
val PagePad = 16.dp

/** Скруглённая «бумага» с тонкой рамкой вместо тени: тень на бумаге не лежит. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(14.dp),
            content = content,
        )
    }
}

/** Заголовок раздела: капслок мелким — так он не спорит с именами сущностей. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Пустой экран — это тоже сообщение: почему пусто и что с этим делать. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(PagePad),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/* ================= движение ================= */

/**
 * Можно ли анимировать.
 *
 * Уважаем системный масштаб длительности: человек, выключивший анимации в
 * «Специальных возможностях», сделал это сознательно, и пульсирующая точка на
 * его экране — не забота, а навязчивость. При нуле показываем ту же метку
 * статично, ничего не теряя в смысле.
 */
@Composable
fun motionOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale > 0f
    }
}

/** Пульсирующая точка: «модель думает» без единого лишнего слова. */
@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val alpha = if (motionOn()) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        ).value
    } else {
        0.75f
    }
    Box(modifier.size(size).background(color.copy(alpha = alpha), CircleShape))
}

/**
 * Три бегущие точки — там, где нужно сказать «идёт работа» строкой, а не меткой.
 *
 * Смещение старта, а не три отдельных анимации: так волна идёт слева направо
 * ровно, и точки не сходятся в одну фазу через минуту работы.
 */
@Composable
fun WorkingDots(color: Color, modifier: Modifier = Modifier) {
    val animate = motionOn()
    val transition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha = if (animate) {
                transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 620, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(i * 200),
                    ),
                    label = "dot$i",
                ).value
            } else {
                0.6f
            }
            Box(Modifier.size(5.dp).background(color.copy(alpha = alpha), CircleShape))
        }
    }
}

/* ================= состояния ================= */

/**
 * Метка состояния сессии.
 *
 * Форма и насыщенность, а не цвет: залитый круг — ждёт человека, пульс — думает,
 * контур — закончила, квадрат — упёрлась в лимит, бледная точка — просто есть.
 * Отличать их цветом значило бы просить человека помнить легенду из пяти
 * оттенков; форму же видно боковым зрением, без обучения.
 */
@Composable
fun StatusMark(status: Status, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (status) {
            Status.WAITING -> Box(
                Modifier
                    .size(14.dp)
                    .border(1.dp, scheme.primary.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(scheme.primary, CircleShape))
            }
            Status.WORKING -> PulsingDot(scheme.onSurface, size = 9.dp)
            Status.DONE -> Box(
                Modifier
                    .size(10.dp)
                    .border(1.dp, scheme.outline, CircleShape)
            )
            // Лимит и срыв — единственные состояния, которые сами не пройдут.
            // Форма у них общая и заметная: квадрат среди кругов.
            Status.LIMIT -> Box(
                Modifier
                    .size(9.dp)
                    .background(scheme.outline, RoundedCornerShape(2.dp))
            )
            Status.FAILED -> Box(
                Modifier
                    .size(9.dp)
                    .background(scheme.error, RoundedCornerShape(2.dp))
            )
            Status.IDLE -> Box(
                Modifier
                    .size(6.dp)
                    .background(scheme.outlineVariant, CircleShape)
            )
        }
    }
}

/** Словами то же, что метка формой: список читают и глазами, и вслух. */
fun statusWord(status: Status): String = when (status) {
    Status.WAITING -> "спрашивает"
    Status.WORKING -> "работает"
    Status.DONE -> "закончила"
    Status.LIMIT -> "лимит"
    Status.FAILED -> "сорвалась"
    Status.IDLE -> "ждёт"
}

/* ================= связь ================= */

/**
 * Полоска состояния связи в шапке.
 *
 * Переподключение НЕ ошибка: телефон блокируют, он меняет сеть и уходит в
 * карман — обрыв тут обычная жизнь, а не поломка. Поэтому «переподключаюсь»
 * рисуется тем же спокойным тоном, что и «на связи», и только «не вышло»
 * получает предупреждающий фон и кнопку.
 */
@Composable
fun LinkStrip(link: Link, onRetry: () -> Unit) {
    if (link is Link.Idle) return
    val scheme = MaterialTheme.colorScheme
    val failed = link is Link.Failed
    val background = if (failed) scheme.errorContainer else scheme.surfaceContainer
    val ink = if (failed) scheme.onErrorContainer else scheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = PagePad, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (link) {
                is Link.Online -> Box(Modifier.size(7.dp).background(scheme.primary, CircleShape))
                is Link.Connecting, is Link.Retrying -> WorkingDots(scheme.onSurfaceVariant)
                else -> Box(
                    Modifier
                        .size(7.dp)
                        .background(ink, RoundedCornerShape(1.dp))
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                headline(link),
                style = MaterialTheme.typography.labelMedium,
                color = if (link is Link.Online) scheme.onSurfaceVariant else ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed) TextButton(onClick = onRetry) { Text("ещё раз") }
        }
        // Причина обрыва — второй строкой и мельче: она нужна, когда что-то
        // пошло не так, но не должна кричать при обычном переподключении.
        val why = (link as? Link.Retrying)?.why.orEmpty()
        if (why.isNotBlank()) {
            Text(
                why,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 17.dp, top = 2.dp),
            )
        }
    }
}

private fun headline(link: Link): String = when (link) {
    is Link.Idle -> ""
    is Link.Connecting -> "подключаюсь"
    is Link.Online -> "на связи"
    is Link.Retrying ->
        "переподключаюсь · попытка ${link.attempt}" +
            if (link.nextInSeconds > 0) " · через ${link.nextInSeconds} с" else ""
    is Link.Failed -> "не вышло: ${link.why.lineSequence().first()}"
}

/* ================= сообщения ================= */

/** Ошибка: приглушённый кирпич и полоска слева — заметно, но без сирены. */
@Composable
fun Banner(text: String) {
    Message(text, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
}

/** Уведомление о ходе дела — тем же бланком, но без тревожного тона. */
@Composable
fun Notice(text: String) {
    Message(text, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Message(text: String, background: Color, ink: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePad, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            // Полоска слева тянется на высоту текста, а высота эта заранее
            // неизвестна — без внутреннего размера она схлопнулась бы в ноль.
            .height(IntrinsicSize.Min)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(ink.copy(alpha = 0.5f))
        )
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            color = ink,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/* ================= переключатель разделов ================= */

/**
 * Сегментированный переключатель вместо вкладок Material.
 *
 * Разделов всегда два-три, и подчёркивание вкладок в такой ситуации занимает
 * больше места, чем несёт смысла. Выбранный сегмент отличается плотностью
 * бумаги и весом шрифта — снова форма, а не краска.
 */
@Composable
fun Segmented(
    items: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) scheme.surfaceContainerLowest else Color.Transparent)
                    .clickable { if (!active) onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Горизонтальная полоска доли: единственный график, который тут уместен. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(ink)
        )
    }
}

/** Русская форма слова «чат» по числу: «1 чат», «2 чата», «5 чатов». */
fun plural(n: Long): String = when {
    n % 10 == 1L && n % 100 != 11L -> "чат"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "чата"
    else -> "чатов"
}
