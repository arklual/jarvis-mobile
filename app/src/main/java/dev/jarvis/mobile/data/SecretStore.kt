package dev.jarvis.mobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Секреты и настройки телефона.
 *
 * Пароли, приватные ключи и отпечатки хостов лежат в EncryptedSharedPreferences:
 * ключ шифрования держит Keystore устройства, то есть без разблокировки телефона
 * содержимое не прочитать даже с доступом к файлам приложения. Это ровно тот
 * случай, ради которого EncryptedSharedPreferences и существует.
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jarvis-secrets",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var machinesRaw: String?
        get() = prefs.getString(KEY_MACHINES, null)
        set(value) = prefs.edit().putString(KEY_MACHINES, value).apply()

    fun secret(machineId: String): String? = prefs.getString("secret:$machineId", null)

    fun setSecret(machineId: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrEmpty()) remove("secret:$machineId") else putString("secret:$machineId", value)
        }.apply()
    }

    fun passphrase(machineId: String): String? = prefs.getString("pass:$machineId", null)

    fun setPassphrase(machineId: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrEmpty()) remove("pass:$machineId") else putString("pass:$machineId", value)
        }.apply()
    }

    /**
     * Отпечатки хостов как изменяемая карта: первый вход запоминаем, смену
     * отвергаем. Запись идёт сразу — иначе перезапуск приложения превратил бы
     * уже известный хост обратно в незнакомый, и защита стала бы декоративной.
     */
    fun knownHosts(): MutableMap<String, String> = object : AbstractMutableMap<String, String>() {
        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = prefs.all.keys
                .filter { it.startsWith(HOST_PREFIX) }
                .mapNotNull { k ->
                    prefs.getString(k, null)?.let { v ->
                        object : MutableMap.MutableEntry<String, String> {
                            override val key = k.removePrefix(HOST_PREFIX)
                            override val value = v
                            override fun setValue(newValue: String): String = value
                        }
                    }
                }
                .toMutableSet()

        override fun put(key: String, value: String): String? {
            val old = prefs.getString(HOST_PREFIX + key, null)
            prefs.edit().putString(HOST_PREFIX + key, value).commit()
            return old
        }

        override fun get(key: String): String? = prefs.getString(HOST_PREFIX + key, null)
    }

    /** Забыть отпечаток: машину переустановили, ключ сменился законно. */
    fun forgetHost(hostPort: String) {
        prefs.edit().remove(HOST_PREFIX + hostPort).apply()
    }

    private companion object {
        const val KEY_MACHINES = "machines"
        const val HOST_PREFIX = "host:"
    }
}
