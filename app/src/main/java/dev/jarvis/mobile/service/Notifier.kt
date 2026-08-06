package dev.jarvis.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
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

    /**
     * Уведомление о событии сессии.
     *
     * `id` стабилен по сессии — повторное событие обновляет карточку, а не
     * плодит вторую. Нажатие открывает СРАЗУ нужный чат: искать сессию в списке
     * после того, как тебе про неё уже сказали, — лишний шаг.
     *
     * `canReply` — только там, где ответ осмыслен (агент ждёт). У «закончила»
     * поле ввода было бы приглашением написать в пустоту.
     */
    fun alert(
        context: Context,
        sessionId: String,
        title: String,
        text: String,
        canReply: Boolean = false,
    ) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled().not()) return
        val open = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ReplyReceiver.EXTRA_SESSION, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        if (canReply) {
            val remote = RemoteInput.Builder(ReplyReceiver.KEY_TEXT)
                .setLabel("Ответить агенту")
                .build()
            val reply = PendingIntent.getBroadcast(
                context,
                sessionId.hashCode(),
                Intent(context, ReplyReceiver::class.java)
                    .putExtra(ReplyReceiver.EXTRA_SESSION, sessionId),
                // MUTABLE обязателен: систему просят ДОПОЛНИТЬ этот intent
                // введённым текстом, а неизменяемый она дополнить не может.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_jarvis, "Ответить", reply)
                    .addRemoteInput(remote)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
        }
        runCatching { NotificationManagerCompat.from(context).notify(sessionId.hashCode(), builder.build()) }
    }

    /** Подтвердить отправку в той же карточке — иначе непонятно, ушло ли. */
    fun replied(context: Context, sessionId: String, text: String, ok: Boolean) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled().not()) return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(if (ok) "Отправлено" else "Не ушло")
            .setContentText(if (ok) text else "$text — попробуй из приложения")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 8_000 else 30_000)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(sessionId.hashCode(), n) }
    }
}
