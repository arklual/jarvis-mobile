package dev.jarvis.mobile.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки приложения.
 *
 * Обычные SharedPreferences, а не защищённые: здесь нет секретов — только
 * тумблеры. Секреты живут в [SecretStore], и смешивать одно с другим значит
 * платить шифрованием за каждое чтение флага.
 */
class Prefs(context: Context) {

    private val p: SharedPreferences =
        context.getSharedPreferences("jarvis-prefs", Context.MODE_PRIVATE)

    /** Уведомлять, когда агент закончил работу. */
    var notifyDone: Boolean
        get() = p.getBoolean(DONE, true)
        set(v) = p.edit().putBoolean(DONE, v).apply()

    /** Уведомлять, когда агент упёрся в вопрос и ждёт человека. */
    var notifyWaiting: Boolean
        get() = p.getBoolean(WAITING, true)
        set(v) = p.edit().putBoolean(WAITING, v).apply()

    /**
     * Держать связь, пока приложение свёрнуто.
     *
     * Без этого уведомлений не будет вовсе: Android останавливает обычные
     * корутины у свёрнутого приложения, и узнать о завершении работы будет
     * неоткуда. Ценой — постоянное уведомление о фоновой службе, которое
     * система показывает и спрятать не даёт.
     */
    var keepAlive: Boolean
        get() = p.getBoolean(KEEP, false)
        set(v) = p.edit().putBoolean(KEEP, v).apply()

    /** Машина, за которой следит фоновая служба. */
    var watchMachine: String?
        get() = p.getString(WATCH, null)
        set(v) = p.edit().putString(WATCH, v).apply()

    private companion object {
        const val DONE = "notifyDone"
        const val WAITING = "notifyWaiting"
        const val KEEP = "keepAlive"
        const val WATCH = "watchMachine"
    }
}
