package com.homebabymonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.homebabymonitor.MonitorBus
import com.homebabymonitor.MonitorState
import com.homebabymonitor.Role
import com.homebabymonitor.core.LocalAddress
import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.PairingLink
import com.homebabymonitor.core.SleepSound
import com.homebabymonitor.core.VideoQuality
import org.webrtc.SurfaceViewRenderer
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun MonitorScreen(
    state: MonitorState,
    startBaby: () -> Unit,
    startParent: (String, String) -> Unit,
    scanQr: () -> Unit,
    stop: () -> Unit,
    talk: (Boolean) -> Unit,
    night: (Boolean) -> Unit,
    torch: (Boolean) -> Unit,
    quality: (VideoQuality) -> Unit,
    music: (SleepSound, Int, Int) -> Unit
) {
    val browser = LocalUriHandler.current
    Column(Modifier.fillMaxSize().background(base).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = teal, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Text("Домашняя няня", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(if (state.connected) teal else faint, CircleShape))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (state.role) {
                null -> WelcomeScreen(startBaby, startParent, scanQr)
                Role.BABY -> BabyCameraScreen(state, stop)
                Role.PARENT -> ParentMonitorScreen(state, stop, talk, night, torch, quality, music)
            }
            if (state.error.isNotBlank()) {
                Surface(color = Color(0xFF463038), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = coral)
                        Spacer(Modifier.width(10.dp))
                        Text(state.error, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            Text("Бета-версия · Не заменяет присмотр за ребёнком", color = muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { browser.openUri("https://github.com/Kronv3rk/home-baby-monitor/blob/main/PRIVACY.md") }) {
                    Text("Конфиденциальность", fontSize = 12.sp)
                }
                TextButton(onClick = { browser.openUri("https://github.com/Kronv3rk/home-baby-monitor/blob/main/THIRD_PARTY_NOTICES.md") }) {
                    Text("Лицензии", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WelcomeScreen(startBaby: () -> Unit, startParent: (String, String) -> Unit, scanQr: () -> Unit) {
    var showManual by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    Text("Одна сеть. Два телефона.", fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, color = Color.White)
    Text("Телефон с камерой остаётся в комнате ребёнка. Второй показывает видео и воспроизводит звук.",
        color = muted, fontSize = 14.sp, lineHeight = 21.sp)
    ActionCard(Icons.Default.Videocam, "Камера", "Оставить телефон у ребёнка", startBaby)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PhoneAndroid, null, tint = teal)
            Spacer(Modifier.width(10.dp))
            Text("Родительский телефон", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
        Button(onClick = scanQr, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.QrCodeScanner, null)
            Spacer(Modifier.width(10.dp))
            Text("Сканировать QR-код")
        }
        TextButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "Скрыть ручное подключение" else "Ввести код вручную")
        }
        if (showManual) {
            OutlinedTextField(value = pin,
                onValueChange = { pin = it.uppercase(Locale.ROOT).filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == ' ' }.take(28) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Код с экрана камеры") })
            OutlinedTextField(value = ip, onValueChange = { ip = it.trim().take(45) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("IP-адрес (если поиск не работает)") })
            Button(onClick = { startParent(ip, pin) },
                enabled = PairingCode.normalize(pin) != null,
                modifier = Modifier.fillMaxWidth()) { Text("Подключиться") }
        }
    }
    Text("Работает через домашний Wi-Fi, без облака и регистрации.", color = muted, fontSize = 12.sp)
}

@Composable
private fun BabyCameraScreen(state: MonitorState, stop: () -> Unit) {
    StatusHeader("Камера", state)
    val qrContent = remember(state.localIp, state.pairingPin) {
        runCatching { PairingLink.create(state.localIp, state.pairingPin) }.getOrNull()
    }
    Panel {
        Text("Подключить второй телефон", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (qrContent != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PairingQr(qrContent)
            }
            Text("Откройте «Сканировать QR-код» на втором телефоне.", color = muted, fontSize = 13.sp)
        }
        HorizontalDivider(color = faint)
        Text("Код для ручного подключения", color = muted, fontSize = 12.sp)
        Text(state.pairingPin, color = Color.White, fontSize = 20.sp, letterSpacing = 1.sp,
            lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        Detail("IP-адрес", state.localIp)
    }
    Panel {
        Text("Состояние камеры", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Detail("Видео", state.actualVideo.ifEmpty { "Ожидаем подключение" })
        Detail("Режим", VideoQuality.fromWire(state.videoQuality)?.title ?: "HD · 30")
        Detail("Ночной режим", if (state.nightMode) "Включён" else "Выключен")
        Detail("Мелодия", SleepSound.fromWire(state.musicSound)?.title ?: "Выключено")
    }
    Text("Экран можно погасить. Не закрывайте приложение через список запущенных.",
        color = muted, fontSize = 12.sp, lineHeight = 18.sp)
    OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text("Остановить камеру") }
}

@Composable
private fun ParentMonitorScreen(
    state: MonitorState, stop: () -> Unit, talk: (Boolean) -> Unit,
    night: (Boolean) -> Unit, torch: (Boolean) -> Unit,
    quality: (VideoQuality) -> Unit, music: (SleepSound, Int, Int) -> Unit
) {
    StatusHeader("Монитор", state)
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp))
        .background(Color.Black), contentAlignment = Alignment.Center) {
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
        if (!state.connected) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.WifiOff, null, tint = muted, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(6.dp))
                Text("Нет видеосигнала", color = Color.White, fontSize = 13.sp)
            }
        }
        Surface(modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            color = Color(0xD919222B), shape = RoundedCornerShape(50)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (state.connected) teal else coral, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (state.connected) "Соединено" else "Отключено", color = Color.White, fontSize = 12.sp)
            }
        }
        if (state.connected && state.actualVideo.isNotBlank()) {
            Text(state.actualVideo, color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .background(Color(0xAA182027), RoundedCornerShape(8.dp)).padding(6.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(modifier = Modifier.weight(1f), color = panel, shape = RoundedCornerShape(15.dp)) {
            Column(Modifier.padding(15.dp)) {
                Text("Заряд камеры", color = muted, fontSize = 12.sp)
                Text(state.remoteBattery?.let { "$it %${if (state.charging) " · зарядка" else ""}" } ?: "—",
                    color = Color.White, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            }
        }
        Surface(modifier = Modifier.weight(1f), color = panel, shape = RoundedCornerShape(15.dp)) {
            Column(Modifier.padding(15.dp)) {
                Text("Звук в комнате", color = muted, fontSize = 12.sp)
                Text(if (state.lastSoundAt > 0) "Был громкий звук" else "Без тревог",
                    color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }
    Panel {
        Text("Качество видео", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoQuality.entries.forEach { mode ->
                FilterChip(selected = state.videoQuality == mode.name,
                    onClick = { quality(mode) }, label = { Text(mode.title) })
            }
        }
        Text("60 кадров/с и 2K зависят от камеры, кодировщика и Wi-Fi. Выбранный режим — запрос, фактический формат показан на видео.",
            color = muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
    Panel {
        Text("Камера и звук", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.nightMode, onClick = { night(!state.nightMode) },
                label = { Text("Ночная съёмка") }, leadingIcon = { Icon(Icons.Default.NightsStay, null, Modifier.size(16.dp)) })
            FilterChip(selected = state.torchOn, onClick = { torch(!state.torchOn) },
                label = { Text("Фонарик") }, leadingIcon = { Icon(Icons.Default.FlashlightOn, null, Modifier.size(16.dp)) })
        }
        Text("Удерживайте, чтобы говорить", color = muted, fontSize = 12.sp)
        Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures(onPress = {
                talk(true)
                try { tryAwaitRelease() } finally { talk(false) }
            }) }, color = if (state.talking) Color(0xFF4DA78E) else teal,
            shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(17.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, null, tint = base)
                Spacer(Modifier.width(8.dp))
                Text(if (state.talking) "Говорите…" else "Говорить", color = base, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    SleepMusicPanel(state, music)
    OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text("Завершить сеанс") }
}

@Composable
private fun SleepMusicPanel(state: MonitorState, onMusic: (SleepSound, Int, Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(30) }
    var volume by remember { mutableFloatStateOf(20f) }
    val playing = SleepSound.fromWire(state.musicSound) ?: SleepSound.OFF
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = teal)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Колыбельные", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Звучат на телефоне у ребёнка", color = muted, fontSize = 12.sp)
            }
        }
        SleepSound.entries.filter { it != SleepSound.OFF }.forEach { sound ->
            Surface(onClick = { onMusic(if (playing == sound) SleepSound.OFF else sound, minutes, volume.roundToInt()) },
                color = if (playing == sound) Color(0xFF275247) else faint, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (playing == sound) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null, tint = if (playing == sound) teal else muted)
                    Spacer(Modifier.width(10.dp))
                    Text(sound.title, color = Color.White, modifier = Modifier.weight(1f))
                    if (playing == sound) Text("Играет", color = teal, fontSize = 12.sp)
                }
            }
        }
        if (playing != SleepSound.OFF) {
            Detail("Осталось", "${(state.musicRemainingSeconds / 60).coerceAtLeast(0)} мин")
            TextButton(onClick = { onMusic(SleepSound.OFF, minutes, volume.roundToInt()) }) {
                Text("Остановить музыку")
            }
        }
        Text("Таймер", color = muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30, 60).forEach { value ->
                FilterChip(selected = minutes == value, onClick = { minutes = value },
                    label = { Text("$value мин") })
            }
        }
        Text("Громкость на телефоне у ребёнка · ${volume.roundToInt()} %", color = muted, fontSize = 12.sp)
        Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..60f,
            onValueChangeFinished = {
                if (playing != SleepSound.OFF) onMusic(playing, minutes, volume.roundToInt())
            })
        Text("Мелодии создаются внутри приложения; чужие аудиозаписи не используются.", color = muted,
            fontSize = 11.sp, lineHeight = 16.sp)
    }
}
