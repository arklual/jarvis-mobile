package dev.jarvis.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val app: AppState = viewModel()
            val state by app.state.collectAsStateWithLifecycle()
            // Телефон уходит в карман с оборванным соединением и возвращается
            // через час. Поднимаем связь на возврате, не дожидаясь очередной
            // паузы backoff, — иначе экран встречает человека надписью
            // «переподключаюсь через 30 с».
            val owner = LocalLifecycleOwner.current
            LaunchedEffect(owner) {
                val obs = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) app.onResume()
                }
                owner.lifecycle.addObserver(obs)
            }
            JarvisTheme { Root(state = state, app = app) }
        }
    }
}
