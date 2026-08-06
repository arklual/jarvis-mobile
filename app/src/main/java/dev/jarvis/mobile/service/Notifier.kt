package dev.jarvis.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.jarvis.mobile.MainActivity
import dev.jarvis.mobile.R

/**
 * Уведомления о том, ради чего приложение и держат в кармане: агент закончил
 * или упёрся в вопрос.
 *
 * Два канала, а не один: «спрашивает» человек хочет слышать сразу, а «закончил»
 * часто достаточно увидеть позже. Разделив их, мы отдаём это решение системным
 * настройкам, где ему и место.
 */
object Notifier {

    const val CHANNEL_ALERTS = "agents"
    const val CHANNEL_SERVICE = "watch"

    fun ensureChannels(context: Context) {
        val m = context.getSystemService(NotificationManager::class.java) ?: return
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Агенты", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Агент закончил работу или ждёт ответа"
            }
        )
        m.createNotificationChannel(
            // Низкая важность намеренно: это служебная строка о том, что связь
            // держится, а не новость. Звенеть ею — злоупотребление вниманием.
            NotificationChannel(CHANNEL_SERVICE, "Фоновая связь", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное соединение с машиной, пока приложение свёрнуто"
            }
        )
    }

    /** Уведомление о событии сессии. `id` стабилен по сессии — не плодим дубли. */
    fun alert(context: Context, sessionId: String, title: String, text: String) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled().not()) return
        val open = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(sessionId.hashCode(), n) }
    }
}
