package dev.jarvis.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import dev.jarvis.mobile.service.Notifier
import dev.jarvis.mobile.ui.JarvisTheme
import dev.jarvis.mobile.ui.Root

class MainActivity : ComponentActivity() {

    /**
     * Разрешение на уведомления спрашиваем один раз при запуске.
     *
     * С Android 13 без него уведомления не показываются ВООБЩЕ и молча: тумблер
     * в настройках стоял бы включённым, служба работала бы, а человек не видел
     * бы ничего и считал, что приложение врёт.
     */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Сессия из уведомления.
     *
     * Не читаем `intent` прямо в композиции: у уже запущенной Activity
     * повторное нажатие приходит в `onNewIntent`, `onCreate` не вызывается, а
     * `getIntent()` без `setIntent` продолжает отдавать старый intent — то есть
     * самый частый случай не работал бы вовсе.
     */
    private val pending = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // иначе getIntent() навсегда останется стартовым
        take(intent)
    }

    /** Извлечь и ПОГАСИТЬ намерение: extra, оставленный в intent, сработал бы снова. */
    private fun take(intent: Intent?) {
        val sid = intent?.getStringExtra(Notifier.EXTRA_SESSION) ?: return
        intent.removeExtra(Notifier.EXTRA_SESSION)
        pending.value = sid
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        take(intent)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val app: AppState = viewModel()
            val state by app.state.collectAsStateWithLifecycle()
            // Нажали на уведомление — открываем СРАЗУ ту сессию. Искать её в
            // списке после того, как тебе про неё уже сказали, — лишний шаг.
            //
            // Ключ эффекта — сам идентификатор, а не Unit: с Unit поворот
            // экрана пересоздаёт Activity с тем же intent и снова утаскивает
            // человека в чат, из которого он только что ушёл.
            val fromNote by pending.collectAsStateWithLifecycle()
            LaunchedEffect(fromNote) {
                fromNote?.let {
                    pending.value = null // разовое намерение: второй раз не открываем
                    app.openFromNotification(it)
                }
            }
            // Телефон уходит в карман с оборванным соединением и возвращается
            // через час. Поднимаем связь на возврате, не дожидаясь очередной
            // паузы backoff, — иначе экран встречает человека надписью
            // «переподключаюсь через 30 с».
            val owner = LocalLifecycleOwner.current
            LaunchedEffect(owner) {
                val obs = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> app.onResume()
                        // Экран погас или ушли в другое приложение — дочитывать
                        // чат незачем: смотреть некому, а трафик платный.
                        Lifecycle.Event.ON_STOP -> app.onBackground()
                        else -> Unit
                    }
                }
                owner.lifecycle.addObserver(obs)
            }
            JarvisTheme { Root(state = state, app = app) }
        }
    }
}
