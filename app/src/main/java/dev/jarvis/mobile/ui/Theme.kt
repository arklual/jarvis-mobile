package dev.jarvis.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * «Клевер» настольного Jarvis, перенесённый на телефон.
 *
 * Правило одно и оно жёсткое: краска в интерфейсе ОДНА — зелёный клевер. Всё
 * остальное — оттенки бумаги. Состояния различаются формой, весом и
 * насыщенностью, а не цветом: цветной светофор на списке из десяти сессий
 * превращается в шум, и человек перестаёт замечать именно ту строку, ради
 * которой достал телефон.
 *
 * Динамическую палитру Android (Material You) сознательно не берём: она красит
 * приложение в цвета обоев и ломает единственное, на чём держится вся эта
 * система, — узнаваемость одной краски.
 */

/** Зелёный клевер: тёмный на бумаге, светлый на ночном фоне. */
private val Clover = Color(0xFF0F5132)
private val CloverLight = Color(0xFF7BD389)

private val Light = lightColorScheme(
    primary = Clover,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE9DE),
    onPrimaryContainer = Color(0xFF0A3A24),
    inversePrimary = CloverLight,
    // Вторичный и третичный — тот же клевер, обесцвеченный. Отдельные тона тут
    // означали бы вторую и третью краску, чего мы как раз избегаем.
    secondary = Color(0xFF4A5C50),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E9E3),
    onSecondaryContainer = Color(0xFF23301F),
    tertiary = Color(0xFF3F5B4A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE3E9E3),
    onTertiaryContainer = Color(0xFF23301F),
    background = Color(0xFFFBFBF9),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFFBFBF9),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFEDEDE7),
    onSurfaceVariant = Color(0xFF585B54),
    surfaceTint = Clover,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE7E7E1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F3),
    surfaceContainer = Color(0xFFF2F2ED),
    surfaceContainerHigh = Color(0xFFECECE6),
    surfaceContainerHighest = Color(0xFFE6E6DF),
    inverseSurface = Color(0xFF2D302C),
    inverseOnSurface = Color(0xFFF2F2ED),
    outline = Color(0xFF8E9189),
    outlineVariant = Color(0xFFDBDBD2),
    // Ошибка — приглушённый кирпич, а не сигнальный красный: на телефоне рвётся
    // связь по десять раз на дню, и алый прямоугольник каждый раз врёт про
    // серьёзность происходящего.
    error = Color(0xFF8C3122),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF4E3DF),
    onErrorContainer = Color(0xFF4A150C),
    scrim = Color(0xFF000000),
)

private val Dark = darkColorScheme(
    primary = CloverLight,
    onPrimary = Color(0xFF06281A),
    primaryContainer = Color(0xFF15422C),
    onPrimaryContainer = Color(0xFFA9E7B3),
    inversePrimary = Clover,
    secondary = Color(0xFF9FB3A5),
    onSecondary = Color(0xFF1B2A21),
    secondaryContainer = Color(0xFF29332C),
    onSecondaryContainer = Color(0xFFC7D8CB),
    tertiary = Color(0xFF9FB3A5),
    onTertiary = Color(0xFF1B2A21),
    tertiaryContainer = Color(0xFF29332C),
    onTertiaryContainer = Color(0xFFC7D8CB),
    background = Color(0xFF14161A),
    onBackground = Color(0xFFE6E8E3),
    surface = Color(0xFF14161A),
    onSurface = Color(0xFFE6E8E3),
    surfaceVariant = Color(0xFF23262B),
    onSurfaceVariant = Color(0xFFA7ACA4),
    surfaceTint = CloverLight,
    surfaceBright = Color(0xFF2A2E34),
    surfaceDim = Color(0xFF0E1013),
    surfaceContainerLowest = Color(0xFF0E1013),
    surfaceContainerLow = Color(0xFF17191E),
    surfaceContainer = Color(0xFF1B1E23),
    surfaceContainerHigh = Color(0xFF20242A),
    surfaceContainerHighest = Color(0xFF262A31),
    inverseSurface = Color(0xFFE6E8E3),
    inverseOnSurface = Color(0xFF1B1E23),
    outline = Color(0xFF6C7278),
    outlineVariant = Color(0xFF333840),
    error = Color(0xFFE0A08E),
    onError = Color(0xFF3A1109),
    errorContainer = Color(0xFF452019),
    onErrorContainer = Color(0xFFF0CFC6),
    scrim = Color(0xFF000000),
)

/**
 * Табличные цифры.
 *
 * Проценты лимитов, номера попыток и счётчики секунд обновляются на месте.
 * Пропорциональные цифры при этом дёргают строку туда-сюда — «через 9 с» и
 * «через 10 с» получаются разной ширины, и глаз ловит движение вместо смысла.
 */
private const val TNUM = "tnum"

private val Base = Typography()

val JarvisTypography = Typography(
    headlineSmall = Base.headlineSmall.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = Base.titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TNUM,
    ),
    titleMedium = Base.titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        fontFeatureSettings = TNUM,
    ),
    titleSmall = Base.titleSmall.copy(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = TNUM,
    ),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TNUM,
    ),
    labelLarge = Base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TNUM,
    ),
    labelSmall = Base.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = TNUM,
    ),
)

/** Пути, команды и содержимое файлов — только моноширинно и только с tnum. */
val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
    fontFeatureSettings = TNUM,
)

val MonoTiny = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    fontFeatureSettings = TNUM,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = JarvisTypography,
        content = content,
    )
}
