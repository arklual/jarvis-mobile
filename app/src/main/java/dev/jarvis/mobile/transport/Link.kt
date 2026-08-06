package dev.jarvis.mobile.transport

/**
 * Состояние связи с машиной.
 *
 * Отдельный тип, а не пара булевых полей: телефон рвёт соединение постоянно —
 * блокировка экрана, переход в фон, смена wifi на мобильную сеть. Разница между
 * «ещё не подключались», «переподключаюсь» и «не выйдет, вот причина» должна
 * быть видна и в коде, и на экране, иначе всё сводится к «не работает».
 */
sealed interface Link {
    data object Idle : Link
    data object Connecting : Link
    data object Online : Link

    /** Связь оборвалась, но мы её восстанавливаем сами. */
    data class Retrying(val attempt: Int, val why: String, val nextInSeconds: Int) : Link

    /** Дальше пробовать бессмысленно: нужен человек (пароль, ключ, отпечаток). */
    data class Failed(val why: String) : Link

    val online: Boolean get() = this is Online
    val busy: Boolean get() = this is Connecting || this is Retrying
}

/**
 * Пауза перед следующей попыткой: 1, 2, 4, 8, 16, 30…с.
 *
 * Та же лесенка, что у настольного Jarvis. Телефон в кармане может быть без
 * сети часами — долбиться в сеть каждую секунду означает греть батарею впустую;
 * но и минутные паузы недопустимы: человек достал телефон и ждёт ответа сейчас.
 */
fun backoffSeconds(attempt: Int): Int = (1 shl attempt.coerceIn(0, 5)).coerceAtMost(30)

/**
 * Стоит ли переподключаться на такой ошибке.
 *
 * Обрыв, таймаут и «сеть недоступна» лечатся повтором. Отказ в доступе, чужой
 * отпечаток хоста и запрет проброса портов повтором не лечатся — пробовать их
 * по кругу значит выглядеть занятым, ничего не делая.
 */
fun isTransient(message: String): Boolean {
    val m = message.lowercase()
    val fatal = listOf(
        "auth fail", "permission denied", "publickey",
        "verif", "host key", "administratively prohibited",
        "no such algorithm",
    )
    if (fatal.any { it in m }) return false
    return true
}
