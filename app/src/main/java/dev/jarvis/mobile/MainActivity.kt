package dev.jarvis.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
