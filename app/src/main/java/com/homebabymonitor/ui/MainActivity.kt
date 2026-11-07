package com.homebabymonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebabymonitor.MonitorBus
import com.homebabymonitor.MonitorService
import com.homebabymonitor.MonitorState
import com.homebabymonitor.Role
import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.LocalAddress
import org.webrtc.SurfaceViewRenderer

private val base = Color(0xFF0C1422)
private val panel = Color(0xFF172437)
private val muted = Color(0xFFA7B8C9)
private val teal = Color(0xFF66E0C7)
private val coral = Color(0xFFFF9387)

class MainActivity : ComponentActivity() {
    private var pendingStart: (() -> Unit)? = null
    private var pendingRequired: List<String> = emptyList()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val needed = pendingRequired
        if (needed.all { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) {
            finishPermissionFlow()
        } else {
            MonitorBus.mutate { state -> state.copy(error = "Разрешите доступ к микрофону, камере и локальной сети в настройках Android") }
            pendingStart = null
        }
        pendingRequired = emptyList()
    }

    private fun requiredPermissions(forBaby: Boolean = true): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (forBaby) add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    private fun startSession(role: Role, ip: String, pin: String) {
        if (role == Role.PARENT && PairingCode.normalize(pin) == null) {
            MonitorBus.mutate { it.copy(error = "Введите весь защищённый код с экрана камеры (20 символов)") }
            return
        }
        if (role == Role.PARENT && ip.isNotBlank() && !LocalAddress.isPrivateIpv4(ip.trim())) {
            MonitorBus.mutate { it.copy(error = "Используйте локальный IPv4-адрес камеры (например, 192.168.1.15)") }
            return
        }
        val request = requiredPermissions(role == Role.BABY)
        val action = {
            val intent = Intent(this, MonitorService::class.java).apply {
                this.action = if (role == Role.BABY) MonitorService.ACTION_BABY else MonitorService.ACTION_PARENT
                putExtra(MonitorService.EXTRA_IP, ip)
                putExtra(MonitorService.EXTRA_PIN, pin)
            }
            ContextCompat.startForegroundService(this, intent)
        }
        val missing = request.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        pendingStart = action
        pendingRequired = request
        if (missing.isEmpty()) finishPermissionFlow()
        else permissions.launch(missing.toTypedArray())
    }

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val start = pendingStart
        pendingStart = null
        if (granted) start?.invoke()
        else MonitorBus.mutate { state ->
            state.copy(error = "Без разрешения на уведомления нельзя надёжно предупреждать о потере связи. Разрешите уведомления в настройках Android.")
        }
    }

    private fun finishPermissionFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Ask before launching the camera/microphone FGS, while the activity is visible.
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val start = pendingStart
            pendingStart = null
            start?.invoke()
        }
    }

    private fun sendCommand(action: String, value: Boolean? = null) {
        startService(Intent(this, MonitorService::class.java).apply {
            this.action = action
            if (value != null) putExtra(MonitorService.EXTRA_VALUE, value)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by MonitorBus.state.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = darkColorScheme(
                background = base, surface = panel,
                primary = teal, onPrimary = Color(0xFF081920),
                secondary = muted, onSurface = Color.White,
                error = coral
            )) {
                Screen(state,
                    startBaby = { startSession(Role.BABY, "", "") },
                    startParent = { ip, pin -> startSession(Role.PARENT, ip, pin) },
                    stop = { sendCommand(MonitorService.ACTION_STOP) },
                    talk = { on -> sendCommand(MonitorService.ACTION_TALK, on) },
                    night = { on -> sendCommand(MonitorService.ACTION_NIGHT, on) },
                    torch = { on -> sendCommand(MonitorService.ACTION_TORCH, on) }
                )
            }
        }
    }
}

@Composable
private fun Screen(
    state: MonitorState, startBaby: () -> Unit, startParent: (String, String) -> Unit,
    stop: () -> Unit, talk: (Boolean) -> Unit, night: (Boolean) -> Unit, torch: (Boolean) -> Unit
) {
    var ip by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var setupParent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(base).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = teal, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(10.dp))
            Text("Домашняя няня", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (state.connected) teal else muted))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.role == null) {
                Text("Рядом, даже когда вы в другой комнате", fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Оба телефона подключаются к одному домашнему Wi-Fi. Интернет не нужен.", color = muted, lineHeight = 22.sp)
                Spacer(Modifier.height(8.dp))
                RoleCard(Icons.Default.Videocam, "Камера ребёнка", "Поставьте телефон возле кроватки. Видео и звук будут передаваться в фоне.") { startBaby() }
                RoleCard(Icons.Default.RemoveRedEye, "Родительский телефон", "Смотрите трансляцию, слушайте комнату и получайте предупреждения.") { setupParent = !setupParent }
                if (setupParent) {
                    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Подключение к камере", color = Color.White, fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = pin, onValueChange = { pin = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == ' ' }.uppercase(Locale.ROOT).take(28) },
                                label = { Text("Код с телефона у ребёнка") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = ip, onValueChange = { ip = it.trim().take(45) },
                                label = { Text("IP камеры (необязательно)") },
                                supportingText = { Text("Оставьте пустым для автоматического поиска") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { startParent(ip, pin) }, enabled = PairingCode.normalize(pin) != null, modifier = Modifier.fillMaxWidth()) {
                                Text("Подключиться")
                            }
                        }
                    }
                }
                Surface(color = Color(0xFF102C34), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wifi, null, tint = teal)
                        Spacer(Modifier.width(10.dp))
                        Text("Только локальная сеть. Без аккаунтов и облака.", color = Color(0xFFD3EDE7), fontSize = 13.sp)
                    }
                }
            } else if (state.role == Role.BABY) {
                Header("Камера ребёнка", state)
                Surface(color = panel, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Videocam, null, tint = teal, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Защищённый код подключения", color = muted, fontSize = 14.sp)
                        Text(state.pairingPin.split("-").let { it.take(3).joinToString("  ") + "\n" + it.drop(3).joinToString("  ") }, fontSize = 23.sp, lineHeight = 38.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("Введите все группы на втором телефоне. Код не передаётся по Wi-Fi.", color = muted, fontSize = 13.sp)
                    }
                }
                InfoLine("IP камеры", state.localIp)
                InfoLine("Видео", "640 × 480 · до 20 кадров/с")
                InfoLine("Ночной режим", if (state.nightMode) "Включён" else "Выключен")
                Text("После запуска можно погасить экран. Не закрывайте приложение принудительно и отключите для него ограничения фоновой работы.", color = muted, fontSize = 13.sp, lineHeight = 20.sp)
                Button(onClick = stop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF382733)), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.StopCircle, null); Spacer(Modifier.width(8.dp)); Text("Остановить камеру")
                }
            } else {
                Header("Родительский монитор", state)
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(22.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView(factory = { context ->
                        SurfaceViewRenderer(context).apply {
                            init(MonitorBus.eglBase.eglBaseContext, null)
                            setEnableHardwareScaler(true)
                            setMirror(false)
                            MonitorBus.bindRenderer(this)
                        }
                    }, modifier = Modifier.fillMaxSize(), onRelease = {
                        MonitorBus.bindRenderer(null)
                        it.release()
                    })
                    if (!state.connected) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.WifiOff, null, tint = muted, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Ожидаем видеосигнал", color = Color.White)
                    }
                    Row(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xBB0C1422)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if (state.connected) teal else coral, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.connected) "В эфире" else "Нет сигнала", color = Color.White, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoCard(Modifier.weight(1f), "Заряд камеры", state.remoteBattery?.let { "$it%${if (state.charging) " ⚡" else ""}" } ?: "—")
                    InfoCard(Modifier.weight(1f), "Звук в комнате", if (state.lastSoundAt == 0L) "Нет тревог" else "Был в ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.lastSoundAt))}")
                }
                Text("Удерживайте кнопку, чтобы говорить с ребёнком", color = muted, fontSize = 13.sp)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(if (state.talking) Color(0xFF257E73) else teal)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            talk(true)
                            try { tryAwaitRelease() } finally { talk(false) }
                        })
                    }
                    .padding(19.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, null, tint = Color(0xFF081920))
                        Spacer(Modifier.width(9.dp))
                        Text(if (state.talking) "Говорите…" else "Нажмите и говорите", color = Color(0xFF081920), fontWeight = FontWeight.Bold)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControlButton(Modifier.weight(1f), Icons.Default.NightsStay, "Ночная съёмка", state.nightMode) { night(!state.nightMode) }
                    ControlButton(Modifier.weight(1f), Icons.Default.FlashlightOn, "Фонарик", state.torchOn) { torch(!state.torchOn) }
                }
                Text("При выключенном экране звук и оповещения продолжают работать. Видеокадры снова будут видны после включения экрана.", fontSize = 13.sp, lineHeight = 20.sp, color = muted)
                OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text("Завершить наблюдение") }
            }
            if (state.error.isNotBlank()) {
                Surface(color = Color(0xFF422631), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = coral)
                        Spacer(Modifier.width(10.dp))
                        Text(state.error, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable private fun Header(title: String, state: MonitorState) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.White)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (state.connected) Icons.Default.CheckCircle else Icons.Default.Sync, null, tint = if (state.connected) teal else muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(state.status, color = muted, fontSize = 13.sp)
    }
}

@Composable private fun RoleCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String, onClick: () -> Unit) {
    Surface(color = panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(19.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(53.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF243D4A)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = teal, modifier = Modifier.size(29.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(description, color = muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = muted)
        }
    }
}

@Composable private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = muted)
        Text(value, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun InfoCard(modifier: Modifier, label: String, value: String) {
    Surface(modifier = modifier, color = panel, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text(label, color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable private fun ControlButton(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), color = if (selected) Color(0xFF1D5152) else panel, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) teal else muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
