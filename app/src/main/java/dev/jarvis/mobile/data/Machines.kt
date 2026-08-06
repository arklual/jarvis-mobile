package dev.jarvis.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Машина, к которой ходит телефон.
 *
 * Пароль и приватный ключ здесь не хранятся в открытом виде: их держит
 * [SecretStore] поверх EncryptedSharedPreferences. Здесь только то, что не
 * является секретом само по себе.
 */
@Serializable
data class Machine(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    /** Порт узла на петле той машины — `JARVIS_NODE_TCP` в его окружении. */
    val nodePort: Int = 7717,
    val authKind: AuthKind = AuthKind.KEY,
)

@Serializable
enum class AuthKind { KEY, PASSWORD }

/** Список машин целиком: он маленький и правится редко. */
@Serializable
data class Machines(val items: List<Machine> = emptyList()) {
    fun byId(id: String): Machine? = items.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun decode(raw: String?): Machines =
            if (raw.isNullOrBlank()) Machines()
            else runCatching { json.decodeFromString<Machines>(raw) }.getOrElse { Machines() }

        fun encode(m: Machines): String = json.encodeToString(serializer(), m)
    }
}
