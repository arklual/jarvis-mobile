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
        if (prefs.keepAlive && prefs.watchMachine != null) WatchService.start(context)
    }
}
