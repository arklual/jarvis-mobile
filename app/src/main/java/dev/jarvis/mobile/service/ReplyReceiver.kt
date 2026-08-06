package dev.jarvis.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Ответ агенту прямо из шторки.
 *
 * Ради этого уведомления и существуют: узнать, что агент ждёт, и разблокировать
 * его, не открывая приложение и не разыскивая сессию. Открыть → найти → войти в
 * чат — три шага там, где нужен один.
 *
 * Сам приёмник ничего не отправляет: соединение и реестр пан держит уже
 * работающая служба, и поднимать ради одной строки второй ssh-туннель значит
 * платить секундами ожидания за то, что уже есть.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) {
            // RemoteImput уже показал спиннер: не обновив карточку, мы оставили
            // бы его крутиться навсегда.
            Notifier.alert(context, sessionId, "Пустой ответ", "Ничего не отправил", canReply = true)
            return
        }
        WatchService.sendReply(context, sessionId, text)
    }

    companion object {
        const val EXTRA_SESSION = "sessionId"
        const val KEY_TEXT = "reply"
    }
}
