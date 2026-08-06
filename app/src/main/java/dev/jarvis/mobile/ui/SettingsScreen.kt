package dev.jarvis.mobile.ui


import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jarvis.mobile.AppState
import dev.jarvis.mobile.UiState

/**
 * Настройки: единственное, что тут решают, — стоит ли телефону дёргать человека.
 *
 * Экран честно объясняет цену: уведомления работают только при живом фоновом
 * соединении, а фоновое соединение — это постоянная плашка в шторке и расход
 * батареи. Прятать эту связь значит обещать то, чего не будет: свёрнутое
 * приложение Android просто усыпляет, а push-канала в этой схеме нет — узел
 * стоит на чужой машине и наружу не смотрит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, app: AppState, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = state.prefs

    // Разрешение спрашиваем ровно тогда, когда человек включил уведомления:
    // диалог при первом запуске он бы отклонил, не поняв, о чём его просят.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ назад") } },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PagePad),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Уведомления")
            PaperCard(Modifier.fillMaxWidth()) {
                Toggle(
                    title = "Когда агент закончил",
                    hint = "Работа доделана и ждёт, чтобы её посмотрели.",
                    checked = prefs.notifyDone,
                ) {
                    if (it) askIfNeeded()
                    app.setPref(done = it)
                }
                Spacer(Modifier.height(14.dp))
                Toggle(
                    title = "Когда агент спрашивает",
                    hint = "Агент встал на вопросе и без ответа дальше не пойдёт.",
                    checked = prefs.notifyWaiting,
                ) {
                    if (it) askIfNeeded()
                    app.setPref(waiting = it)
                }
            }

            SectionTitle("Фоновое соединение")
            PaperCard(Modifier.fillMaxWidth()) {
                Toggle(
                    title = "Держать связь, пока приложение свёрнуто",
                    hint = "Телефон продолжает слушать узел и потому успевает " +
                        "заметить, что агент закончил или спросил.",
                    checked = prefs.keepAlive,
                ) {
                    if (it) askIfNeeded()
                    app.setPref(keepAlive = it)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Цена честная: система показывает постоянное уведомление о фоновой " +
                        "службе и не даёт его спрятать, а связь по SSH расходует батарею. " +
                        "Выключишь — приложение будет узнавать новости только когда открыто.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!prefs.keepAlive && (prefs.notifyDone || prefs.notifyWaiting)) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Сейчас уведомления включены, но в фоне телефон ничего не слушает — " +
                            "они придут только при открытом приложении.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Служба переднего плана от засыпания НЕ спасает: производители
                // усыпляют фон независимо от неё, и уведомления пропадают молча.
                // Единственное лекарство — исключение, и просить его надо явно.
                if (prefs.keepAlive && !prefs.batteryExempt) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Телефон может усыплять приложение — тогда уведомления перестанут " +
                            "приходить, и молча. Исключи Jarvis из оптимизации батареи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    // refreshPrefs здесь не зовём: startActivity асинхронен, и мы
                    // прочитали бы старое состояние. Его перечитает onResume.
                    Button(onClick = { askBatteryExemption(context) }) {
                        Text("Разрешить работу в фоне")
                    }
                }
            }
            Spacer(Modifier.height(PagePad))
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Попросить исключение из оптимизации батареи.
 *
 * Прямой запрос, если система его принимает, иначе — экран со списком: на части
 * прошивок прямой intent закрыт, и упереться в него молча хуже, чем открыть
 * список и дать человеку найти приложение самому.
 */
private fun askBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }
        .recoverCatching { context.startActivity(fallback) }
}
