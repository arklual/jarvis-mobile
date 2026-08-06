package dev.jarvis.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            JarvisTheme { Root(state = state, app = app) }
        }
    }
}
