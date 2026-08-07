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
     * Уведомлять, когда работа встала сама по себе.
     *
     * Отдельно от «закончил»: там работа сделана и её ждут посмотреть, а тут
     * она СТОИТ — упёрлись в лимит или ход сорвался ошибкой. Свести их в один
     * тумблер значило бы предложить человеку выбор между «знать про обе вещи»
     * и «не знать ни про одну».
     */
    var notifyStuck: Boolean
        get() = p.getBoolean(STUCK, true)
        set(v) = p.edit().putBoolean(STUCK, v).apply()

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
        const val STUCK = "notifyStuck"
        const val KEEP = "keepAlive"
        const val WATCH = "watchMachine"
    }
}
