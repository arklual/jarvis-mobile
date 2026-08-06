package dev.jarvis.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jarvis.mobile.data.Prefs

/**
 * Поднять слежение после перезагрузки телефона.
 *
 * Без этого тумблер «уведомлять» переживал перезагрузку, а служба нет: человек
 * видел включённый флаг и не получал ничего. Молчание вместо уведомлений хуже,
 * чем их отсутствие, — на него полагаются.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        // Система вправе запретить старт службы переднего плана из загрузки.
        // Отказ — не повод падать: связь поднимется при первом открытии
        // приложения, это делает AppState на старте.
        if (prefs.keepAlive && prefs.watchMachine != null) {
            runCatching { WatchService.start(context) }
        }
    }
}
