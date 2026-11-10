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
import androidx.compose.ui.platform.LocalContext
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

/** The requested night mode may deliberately bypass CPU processing at high frame rates. */
private fun nightProcessingLabel(status: String): String = when (status.substringBefore(':')) {
    "OFF" -> "Коррекция выключена"
    "WAITING" -> "Ожидание первых кадров от камеры"
    "SOFTWARE" -> "Коррекция яркости · обработка около ${status.substringAfter(':', "0")} мс/кадр"
    "PASSTHROUGH" -> "Кадры передаются без программной коррекции, чтобы сохранить разрешение и скорость"
    "COOLDOWN" -> "Коррекция временно приостановлена из-за нагрузки на процессор"
    else -> "Состояние коррекции уточняется"
}

@Composable
internal fun MonitorScreen(
    state: MonitorState,
    crashAvailable: Boolean,
    shareCrash: () -> Unit,
    startBaby: () -> Unit,
    startParent: (String, String) -> Unit,
    scanQr: () -> Unit,
    stop: () -> Unit,
    talk: (Boolean) -> Unit,
    night: (Boolean) -> Unit,
    torch: (Boolean) -> Unit,
    quality: (VideoQuality) -> Unit,
    cameraMic: (Boolean) -> Unit,
    speaker: (Boolean) -> Unit,
    playbackVolume: (Int) -> Unit,
    music: (SleepSound, Int, Int) -> Unit,
    musicVolume: (Int) -> Unit,
    acknowledgeAlert: () -> Unit
) {
    val context = LocalContext.current
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(state.role) { if (state.role != Role.PARENT) fullscreen = false }
    if (state.role == Role.PARENT && fullscreen) {
        FullscreenMonitor(state, onClose = { fullscreen = false })
        return
    }
    var legalDocument by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(base).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = teal, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Text("Домашняя няня", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(if (state.connected) teal else faint, CircleShape))
        }
        if (state.role == Role.PARENT && state.incidentTitle.isNotBlank()) {
            Surface(color = Color(0xFF57342D), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null, tint = coral)
                        Spacer(Modifier.width(9.dp))
                        Text(state.incidentTitle, color = Color.White,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (state.incidentAcknowledged)
                        "Предупреждение подтверждено. Проверка соединения продолжается."
                        else "Если проблема сохраняется, предупреждение повторится через минуту.",
                        color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
                    if (!state.incidentAcknowledged) TextButton(onClick = acknowledgeAlert) {
                        Text("Подтвердить предупреждение")
                    }
                }
            }
        }
        if (state.error.isNotBlank()) {
            Surface(
                color = Color(0xFF463038),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = coral)
                    Spacer(Modifier.width(10.dp))
                    Text(state.error, color = Color.White, fontSize = 13.sp,
                        lineHeight = 18.sp, modifier = Modifier.weight(1f), maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (state.role) {
                null -> WelcomeScreen(startBaby, startParent, scanQr)
                Role.BABY -> BabyCameraScreen(state, stop, quality, cameraMic,
                    speaker, playbackVolume, music, musicVolume)
                Role.PARENT -> ParentMonitorScreen(state, stop, talk, night, torch,
                    speaker, playbackVolume, music, musicVolume, onFullscreen = { fullscreen = true })
            }
            Text("Не заменяет присмотр за ребёнком", color = muted, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { legalDocument = "PRIVACY.md" }) {
                    Text("Конфиденциальность", fontSize = 12.sp)
                }
                TextButton(onClick = { legalDocument = "SAFETY_AND_TERMS.md" }) {
                    Text("Безопасность", fontSize = 12.sp)
                }
                TextButton(onClick = { legalDocument = "THIRD_PARTY_NOTICES.md" }) {
                    Text("Лицензии", fontSize = 12.sp)
                }
            }
            if (crashAvailable) {
                TextButton(onClick = shareCrash) { Text("Отправить отчёт о последнем сбое") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (legalDocument != null) {
        val doc = legalDocument!!
        val title = when (doc) {
            "PRIVACY.md" -> "Конфиденциальность"
            "SAFETY_AND_TERMS.md" -> "Безопасность"
            else -> "Лицензии"
        }
        val content = remember(doc) {
            runCatching {
                context.assets.open("legal/$doc").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .replace(Regex("(?m)^#{1,5} +"), "")
                    .replace("**", "")
                    .replace("`", "")
            }.getOrElse { "Не удалось открыть документ" }
        }
        AlertDialog(
            onDismissRequest = { legalDocument = null },
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(content, fontSize = 13.sp, lineHeight = 19.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { legalDocument = null }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun WelcomeScreen(startBaby: () -> Unit, startParent: (String, String) -> Unit, scanQr: () -> Unit) {
    var showManual by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    Text("Подключение", fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, color = Color.White)
    Text("Один телефон передаёт видео и звук, второй показывает трансляцию.",
        color = muted, fontSize = 14.sp, lineHeight = 21.sp)
    ActionCard(Icons.Default.Videocam, "Камера", "В комнате ребёнка, вне его досягаемости", startBaby)
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
    Text("Оба телефона должны быть в одной Wi-Fi-сети.", color = muted, fontSize = 12.sp)
}

@Composable
private fun BabyCameraScreen(
    state: MonitorState, stop: () -> Unit, quality: (VideoQuality) -> Unit,
    cameraMic: (Boolean) -> Unit, speaker: (Boolean) -> Unit,
    playbackVolume: (Int) -> Unit,
    music: (SleepSound, Int, Int) -> Unit, musicVolume: (Int) -> Unit
) {
    var revealPairing by remember { mutableStateOf(false) }
    LaunchedEffect(state.connected) { if (state.connected) revealPairing = false }
    val showPairing = com.homebabymonitor.core.PairingVisibility.show(state.connected, revealPairing)
    StatusHeader("Камера", state)

    Text("Что видит камера", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    VideoPane(state = state, cameraPreview = true,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)))
    Text("Предпросмотр работает и до подключения монитора. Установите камеру, ориентируясь по изображению.",
        color = muted, fontSize = 12.sp, lineHeight = 18.sp)

    val qrContent = remember(state.localIp, state.pairingPin) {
        runCatching { PairingLink.create(state.localIp, state.pairingPin) }.getOrNull()
    }
    Panel {
        Text("Подключить второй телефон", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (showPairing) {
            if (qrContent != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PairingQr(qrContent)
                }
                Text("Сканируйте QR-код со второго телефона.", color = muted, fontSize = 13.sp)
            } else {
                Text("QR-код появится при подключении к Wi-Fi.", color = muted, fontSize = 13.sp)
            }
            HorizontalDivider(color = faint)
            Text("Код для ручного подключения", color = muted, fontSize = 12.sp)
            Text(state.pairingPin, color = Color.White, fontSize = 20.sp, letterSpacing = 1.sp,
                lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text("Код скрыт, пока идёт трансляция.", color = muted, fontSize = 13.sp)
        }
        if (state.connected) {
            TextButton(onClick = { revealPairing = !revealPairing }) {
                Text(if (showPairing) "Скрыть код" else "Показать код")
            }
        }
        Detail("IP-адрес", state.localIp)
    }

    Panel {
        Text("Качество видеопотока", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("«Автоматически» повышает качество на стабильном Wi-Fi и снижает его при задержках, потерях кадров и перегреве.",
            color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoQuality.entries.forEach { mode ->
                FilterChip(selected = state.videoQuality == mode.name,
                    onClick = { quality(mode) }, label = { Text(mode.title) })
            }
        }
        Detail("Выбранный режим", VideoQuality.fromWire(state.videoQuality)?.title ?: "—")
        Detail("Активный режим", VideoQuality.fromWire(state.actualVideoQuality)?.title ?: "Определяется…")
        Detail("Фактическая съёмка", state.actualVideo.ifEmpty { "Запускается…" })
        Detail("Аппаратные кодеки", state.codecSupport.ifBlank { "Проверяются…" })
        Detail("Кодек видеопотока", state.negotiatedCodec.ifBlank { "Согласуется…" })
        Text("4K / 60 к/с доступно только при поддержке камерой и аппаратными кодировщиками обоих телефонов. На слабой сети приоритет у стабильного звука и небольшой задержки.",
            color = muted, fontSize = 12.sp, lineHeight = 18.sp)
    }

    Panel {
        Text("Звук на телефоне камеры", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Микрофон камеры", color = Color.White)
                Text("Передача звука в монитор", color = muted, fontSize = 12.sp)
            }
            Switch(checked = state.cameraMicEnabled, onCheckedChange = cameraMic)
        }
        if (!state.cameraMicEnabled) Text("Когда микрофон выключен, уведомления о громком звуке недоступны.",
            color = coral, fontSize = 12.sp, lineHeight = 18.sp)
        HorizontalDivider(color = faint)
        IncomingAudioControls(state, "Звук с родительского телефона", speaker, playbackVolume)
        Text("Обе стороны могут слышать друг друга одновременно. Следите за громкостью: близко расположенные устройства могут фонить.",
            color = muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    Panel {
        Text("Состояние камеры", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Detail("Режим", VideoQuality.fromWire(state.actualVideoQuality)?.title ?: "—")
        Detail("Ночной режим", if (state.nightMode) "Включён" else "Выключен")
        if (state.nightMode) {
            Text(nightProcessingLabel(state.nightProcessingStatus), color = muted,
                fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (state.cameraRecoveryStatus.isNotBlank())
            Detail("Состояние видеозахвата", state.cameraRecoveryStatus)
        Detail("Звуки для сна", SleepSound.fromWire(state.musicSound)?.title ?: "Выключены")
    }
    SleepMusicPanel(state, music, musicVolume, localControls = true)
    Text("Экран можно погасить. Не закрывайте приложение через список запущенных.",
        color = muted, fontSize = 12.sp, lineHeight = 18.sp)
    OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text("Остановить камеру") }
}

@Composable
private fun ParentMonitorScreen(
    state: MonitorState, stop: () -> Unit, talk: (Boolean) -> Unit,
    night: (Boolean) -> Unit, torch: (Boolean) -> Unit,
    speaker: (Boolean) -> Unit, playbackVolume: (Int) -> Unit,
    music: (SleepSound, Int, Int) -> Unit, musicVolume: (Int) -> Unit,
    onFullscreen: () -> Unit
) {
    StatusHeader("Монитор", state)
    VideoPane(state, cameraPreview = false,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)),
        onExpand = onFullscreen)
    Text("Нажмите значок разворачивания справа вверху для полноэкранного просмотра.",
        color = muted, fontSize = 12.sp, lineHeight = 18.sp)

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
                Text("Громкий звук", color = muted, fontSize = 12.sp)
                Text(if (state.lastSoundAt > 0) "Был сигнал" else "Не обнаружен",
                    color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }

    Panel {
        Text("Видеопоток", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Detail("Настройка камеры", VideoQuality.fromWire(state.videoQuality)?.title ?: "—")
        Detail("Активный режим", VideoQuality.fromWire(state.actualVideoQuality)?.title ?: "Измеряется…")
        Detail("Принимаемый поток", state.actualVideo.ifEmpty { "Нет кадров" })
        Detail("Скорость / кодек / потери", state.networkVideo.ifEmpty { "Измеряется…" })
        Text(state.videoTiming.ifBlank { "Сбор статистики буферизации и декодирования…" },
            color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Время в сетевом буфере и декодере — не полная задержка между камерой и экраном.",
            color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        Detail("Кодеки монитора", state.codecSupport.ifBlank { "Не определены" })
        Text("В автоматическом режиме камера учитывает возможности обоих устройств, полученные кадры, потери и задержку. Реальное качество может быть ниже выбранного режима.",
            color = muted, fontSize = 12.sp, lineHeight = 18.sp)
    }

    Panel {
        Text("Просмотр и двусторонний звук", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        IncomingAudioControls(state, "Слушать комнату ребёнка", speaker, playbackVolume)
        if (!state.cameraMicEnabled) {
            Text("Микрофон камеры отключён на втором телефоне.", color = coral, fontSize = 12.sp)
        } else {
            Text(when (state.microphoneCaptureHealthy) {
                true -> "Микрофон камеры захватывает звук"
                false -> "Нет новых аудиоданных с камеры"
                null -> "Ожидание данных от микрофона камеры"
            }, color = if (state.microphoneCaptureHealthy == false) coral else muted,
                fontSize = 12.sp, lineHeight = 18.sp)
        }
        HorizontalDivider(color = faint)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.nightMode, onClick = { night(!state.nightMode) },
                label = { Text("Ночная съёмка") }, leadingIcon = { Icon(Icons.Default.NightsStay, null, Modifier.size(16.dp)) })
            FilterChip(selected = state.torchOn, onClick = { torch(!state.torchOn) },
                label = { Text("Фонарик") }, leadingIcon = { Icon(Icons.Default.FlashlightOn, null, Modifier.size(16.dp)) })
        }
        if (state.nightMode) {
            Text(nightProcessingLabel(state.nightProcessingStatus), color = muted,
                fontSize = 12.sp, lineHeight = 18.sp)
        }
        Text("Нажмите и удерживайте для разговора", color = muted, fontSize = 12.sp)
        val isTalking by rememberUpdatedState(state.talking)
        Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .pointerInput(state.connected) { detectTapGestures(onPress = {
                if (state.connected && !isTalking) {
                    talk(true)
                    try { tryAwaitRelease() } finally { talk(false) }
                }
            }) }, color = if (state.talking) Color(0xFF4DA78E) else teal,
            shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(17.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, null, tint = base)
                Spacer(Modifier.width(8.dp))
                Text(if (state.talking) "Микрофон включён" else "Говорить", color = base,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Разговор без удерживания", color = Color.White)
                Text("Можно одновременно слушать ребёнка", color = muted, fontSize = 12.sp)
            }
            Switch(checked = state.talking, onCheckedChange = talk, enabled = state.connected)
        }
    }
    SleepMusicPanel(state, music, musicVolume, localControls = false)
    OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text("Завершить сеанс") }
}

@Composable
private fun IncomingAudioControls(
    state: MonitorState, title: String,
    onSpeaker: (Boolean) -> Unit, onVolume: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text("Воспроизведение на этом телефоне", color = muted, fontSize = 12.sp)
        }
        Switch(checked = state.speakerEnabled, onCheckedChange = onSpeaker)
    }
    var volume by remember { mutableFloatStateOf(state.playbackVolume.toFloat()) }
    LaunchedEffect(state.playbackVolume) { volume = state.playbackVolume.toFloat() }
    Text("Громкость входящего голоса · ${volume.roundToInt()} %", color = muted, fontSize = 12.sp)
    Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f,
        enabled = state.speakerEnabled,
        onValueChangeFinished = { onVolume(volume.roundToInt()) })
}

@Composable
private fun SleepMusicPanel(
    state: MonitorState,
    onMusic: (SleepSound, Int, Int) -> Unit,
    onVolume: (Int) -> Unit,
    localControls: Boolean
) {
    var minutes by remember { mutableIntStateOf(30) }
    var volume by remember { mutableFloatStateOf(state.musicVolume.toFloat()) }
    LaunchedEffect(state.musicVolume) { volume = state.musicVolume.toFloat() }
    val playing = SleepSound.fromWire(state.musicSound) ?: SleepSound.OFF
    val enabled = localControls || state.connected
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = teal)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Музыка и звуки для сна", color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Воспроизводятся на телефоне камеры", color = muted, fontSize = 12.sp)
            }
        }
        for (melody in listOf(true, false)) {
            Text(if (melody) "Мелодии" else "Фоновые звуки",
                color = muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            SleepSound.entries.filter { it != SleepSound.OFF && it.melody == melody }.forEach { sound ->
                Surface(onClick = {
                    onMusic(if (playing == sound) SleepSound.OFF else sound, minutes, volume.roundToInt())
                }, enabled = enabled,
                    color = if (playing == sound) Color(0xFF275247) else faint,
                    shape = RoundedCornerShape(12.dp)) {
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
        }
        if (playing != SleepSound.OFF) {
            Detail("Осталось", "${(state.musicRemainingSeconds / 60).coerceAtLeast(0)} мин")
            TextButton(onClick = { onMusic(SleepSound.OFF, minutes, volume.roundToInt()) },
                enabled = enabled) { Text("Остановить звук") }
        }
        Text("Таймер", color = muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30, 60).forEach { time ->
                FilterChip(selected = minutes == time, onClick = { minutes = time },
                    label = { Text("$time мин") })
            }
        }
        Text("Громкость звуков для сна · ${volume.roundToInt()} %", color = muted, fontSize = 12.sp)
        Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..60f,
            enabled = enabled, onValueChangeFinished = { onVolume(volume.roundToInt()) })
        Text("Держите звук негромким и не ставьте телефон вплотную к ребёнку.",
            color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        if (!enabled) Text("Управление звуками появится после подключения камеры.",
            color = muted, fontSize = 12.sp)
    }
}
