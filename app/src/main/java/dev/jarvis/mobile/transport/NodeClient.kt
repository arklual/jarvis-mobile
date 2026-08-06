package dev.jarvis.mobile.transport

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP-клиент узла поверх поднятого ssh-туннеля.
 *
 * Два клиента, а не один, по той же причине, что и в настольном Jarvis:
 * `/events` — длинный опрос до 25 секунд, а ответ и пульт должны падать быстро.
 * Общий таймаут превратил бы недоступный узел в подвисший экран.
 */
class NodeClient(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fast = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val poll = fast.newBuilder().readTimeout(35, TimeUnit.SECONDS).build()

    private fun get(path: String, longPoll: Boolean = false): String {
        val client = if (longPoll) poll else fast
        val req = Request.Builder().url(baseUrl + path).get().build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("узел ответил ${res.code} на $path")
            return body
        }
    }

    private fun post(path: String, payload: String) {
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        fast.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("узел ответил ${res.code} на $path")
        }
    }

    fun hello(): Hello = json.decodeFromString(get("/hello"))

    /** События с курсора. Узел держит запрос до 25 с, если новых нет. */
    fun events(since: Long): EventsPage = json.decodeFromString(get("/events?since=$since", longPoll = true))

    /** Кусок транскрипта. `null` — файла ещё нет: сессия не слала событий. */
    fun file(path: String, from: Long): FileChunk? {
        val req = Request.Builder()
            .url("$baseUrl/file?path=${enc(path)}&from=$from")
            .get()
            .build()
        fast.newCall(req).execute().use { res ->
            if (res.code == 404) return null
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("узел ответил ${res.code} на /file")
            return json.decodeFromString<FileChunk>(body)
        }
    }

    fun panes(): PanesReply = json.decodeFromString(get("/panes"))

    fun projects(): List<RemoteProject> = json.decodeFromString<ProjectsReply>(get("/projects")).projects

    fun reply(pane: String, text: String) = post("/reply", json.encodeToString(ReplyBody.serializer(), ReplyBody(pane, text)))

    fun control(pane: String, cmd: String) = post("/control", json.encodeToString(ControlBody.serializer(), ControlBody(pane, cmd)))

    /** План клавиш в пикер вопроса. Что нажимать — решает клиент, узел играет. */
    fun keys(pane: String, plan: List<KeyStep>) = post("/keys", json.encodeToString(KeysBody.serializer(), KeysBody(pane, plan)))

    /** Поднять сессию и получить пану: по ней видно, что там происходит. */
    fun launch(cwd: String, cmd: String, name: String): LaunchReply {
        val body = json.encodeToString(LaunchBody.serializer(), LaunchBody(cwd, cmd, name))
        val req = Request.Builder()
            .url("$baseUrl/launch")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        fast.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException(errorText(text, res.code))
            return json.decodeFromString(text)
        }
    }

    /** Видимый экран паны — то же, что увидел бы подключившийся к ней. */
    fun screen(pane: String): ScreenReply = json.decodeFromString(get("/screen?pane=${enc(pane)}"))

    /** Текст ошибки узла человеку, а не «rc=502». */
    private fun errorText(body: String, code: Int): String = runCatching {
        json.decodeFromString<ScreenReply>(body).error.ifBlank { "узел ответил $code" }
    }.getOrDefault("узел ответил $code")

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

@kotlinx.serialization.Serializable
private data class ReplyBody(val pane: String, val text: String)

@kotlinx.serialization.Serializable
private data class ControlBody(val pane: String, val cmd: String)

@kotlinx.serialization.Serializable
private data class KeysBody(val pane: String, val keys: List<KeyStep>)

@kotlinx.serialization.Serializable
private data class LaunchBody(val cwd: String, val cmd: String, val name: String)
