package com.homebabymonitor

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.VideoQuality
import com.homebabymonitor.core.SleepSound
import com.homebabymonitor.core.SleepCommand
import com.homebabymonitor.audio.SleepAudioPlayer
import com.homebabymonitor.core.SessionWatchdog
import com.homebabymonitor.core.LocalAddress
import com.homebabymonitor.core.WireMessage
import com.homebabymonitor.net.BabyBeacon
import com.homebabymonitor.net.BabySignalServer
import com.homebabymonitor.net.LocalIp
import com.homebabymonitor.net.ParentConnector
import com.homebabymonitor.net.SignalPipe
import com.homebabymonitor.rtc.RtcEngine
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Service must be launched while the user can see an activity (Android 14+ camera/mic rule). */
class MonitorService : Service() {
    companion object {
        const val ACTION_BABY = "com.homebabymonitor.START_BABY"
        const val ACTION_PARENT = "com.homebabymonitor.START_PARENT"
        const val ACTION_STOP = "com.homebabymonitor.STOP"
        const val ACTION_TALK = "com.homebabymonitor.TALK"
        const val ACTION_NIGHT = "com.homebabymonitor.NIGHT"
        const val ACTION_TORCH = "com.homebabymonitor.TORCH"
        const val ACTION_QUALITY = "com.homebabymonitor.QUALITY"
        const val ACTION_MUSIC = "com.homebabymonitor.MUSIC"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_IP = "ip"
        const val EXTRA_PIN = "pin"
        const val EXTRA_VALUE = "value"
        private const val MAIN_CHANNEL = "home_baby_running"
        private const val ALERT_CHANNEL = "home_baby_alerts"
        private const val NOTIFY_RUNNING = 102
    }

    private var role: Role? = null
    private var engine: RtcEngine? = null
    private var beacon: BabyBeacon? = null
    private var server: BabySignalServer? = null
    private var connector: ParentConnector? = null
    @Volatile private var pipe: SignalPipe? = null
    private var ticker: ScheduledExecutorService? = null
    private val watchdog = SessionWatchdog()
    private var alarmedForOutage = false
    private val running = AtomicBoolean(false)
    private val wifiHandler = Handler(Looper.getMainLooper())
    private var wifiRetry: Runnable? = null
    private var mediaStarted = false
    private val iceConnected = AtomicBoolean(false)
    private val firstVideoFrameReceived = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var torchOn = false
    private var qualityPending = true
    private val sleepPlayer by lazy { SleepAudioPlayer { error -> MonitorBus.mutate { it.copy(error = error) } } }
    private var formerAudioMode: Int? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        setupNotifications()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BABY -> launch(Role.BABY, "", "")
            ACTION_PARENT -> launch(Role.PARENT, intent.getStringExtra(EXTRA_IP).orEmpty(), intent.getStringExtra(EXTRA_PIN).orEmpty())
            ACTION_TALK -> {
                val value = intent.getBooleanExtra(EXTRA_VALUE, false)
                val enabled = value && role == Role.PARENT && MonitorBus.state.value.connected
                engine?.setTalking(enabled)
                MonitorBus.mutate { it.copy(talking = enabled) }
            }
            ACTION_NIGHT -> {
                val value = intent.getBooleanExtra(EXTRA_VALUE, false)
                if (role == Role.PARENT) engine?.sendControl("NIGHT:${if (value) 1 else 0}")
                else if (role == Role.BABY) updateNight(value)
            }
            ACTION_TORCH -> {
                val value = intent.getBooleanExtra(EXTRA_VALUE, false)
                if (role == Role.PARENT) engine?.sendControl("TORCH:${if (value) 1 else 0}")
                else if (role == Role.BABY) updateTorch(value)
            }
            ACTION_QUALITY -> {
                val value = intent.getStringExtra(EXTRA_PROFILE)?.let(VideoQuality::fromWire)
                if (value != null && role == Role.PARENT) {
                    qualityPending = true
                    MonitorBus.mutate { it.copy(videoQuality = value.name) }
                }
            }
            ACTION_MUSIC -> {
                val sound = intent.getStringExtra(EXTRA_SOUND)?.let(SleepSound::fromWire)
                val minutes = intent.getIntExtra(EXTRA_DURATION, 30)
                val volume = intent.getIntExtra(EXTRA_VOLUME, 20)
                if (role == Role.PARENT && sound != null && minutes in setOf(10, 30, 60) && volume in 0..100) {
                    if (engine?.sendControl(SleepCommand(sound, minutes, volume).encode()) == true) {
                        MonitorBus.mutate { it.copy(musicSound = sound.name) }
                    } else MonitorBus.mutate { it.copy(error = "Нет связи с камерой") }
                }
            }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun launch(newRole: Role, ip: String, pin: String) {
        if (running.get()) return
        if (newRole == Role.PARENT && PairingCode.normalize(pin) == null) {
            MonitorBus.mutate { it.copy(error = "Введите код из 20 символов с экрана камеры") }
            stopSelf()
            return
        }
        role = newRole
        val serviceType = if (newRole == Role.BABY)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            val notification = runningNotification(newRole)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_RUNNING, notification, serviceType)
            else startForeground(NOTIFY_RUNNING, notification)
        } catch (e: Exception) {
            MonitorBus.mutate { it.copy(error = "Не удалось запустить фоновый режим: ${e.message}") }
            stopSelf(); return
        }
        running.set(true)
        iceConnected.set(false)
        firstVideoFrameReceived.set(false)
        alarmedForOutage = false
        qualityPending = true
        mediaStarted = false
        watchdog.started(SystemClock.elapsedRealtime())
        MonitorBus.state.value = MonitorState(
            role = newRole,
            localIp = LocalIp.current(this),
            pairingPin = if (newRole == Role.BABY) PairingCode.generate() else "",
            remoteIp = ip,
            status = if (newRole == Role.BABY) "Запускаем камеру…" else "Ищем камеру…"
        )
        awaitLocalWifi(newRole, ip, pin)
    }

    private fun awaitLocalWifi(newRole: Role, ip: String, pin: String) {
        val attempt = object : Runnable {
            override fun run() {
                if (!running.get() || mediaStarted) return
                val route = LocalIp.route(this@MonitorService)
                if (route == null) {
                    MonitorBus.mutate {
                        it.copy(localIp = "—", status = "Ожидание Wi-Fi",
                            error = "Подключите этот телефон к домашнему Wi-Fi. Камера запустится автоматически.")
                    }
                    wifiHandler.postDelayed(this, 2500)
                    return
                }
                mediaStarted = true
                val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (route.network != null) {
                    try {
                        if (!manager.bindProcessToNetwork(route.network))
                            Log.w("MonitorService", "Wi-Fi present but process binding was rejected")
                    } catch (e: Exception) {
                        Log.w("MonitorService", "Wi-Fi process binding unavailable", e)
                    }
                }
                MonitorBus.mutate {
                    it.copy(localIp = route.address, error = "",
                        status = if (newRole == Role.BABY) "Запускаем камеру" else "Подключаемся к камере")
                }
                startSession(newRole, ip, pin, route.address)
            }
        }
        wifiRetry = attempt
        attempt.run()
    }

    private fun startSession(newRole: Role, ip: String, pin: String, wifiIp: String) {
        acquirePowerLocks()
        configureAudioOutput()
        try {
            engine = RtcEngine(
                applicationContext, newRole,
                emitSignaling = { message -> pipe?.send(message) },
                onConnection = { connected -> onMediaConnection(connected) },
                onControl = { message -> handleControl(message) },
                onVideoFrame = { onVideoFrameReceived() },
                onCaptureMetrics = { metrics ->
                    MonitorBus.mutate { it.copy(actualVideo = metrics) }
                    // Receiver frame rate is measured separately by the decoded video sink.
                },
                onError = { error -> MonitorBus.mutate { it.copy(error = error) } }
            ).also { it.start() }
        } catch (e: Exception) {
            MonitorBus.mutate { it.copy(error = "Не удалось запустить видео или звук: ${e.message}") }
            stopSelf(); return
        }
        if (newRole == Role.BABY) {
            val code = MonitorBus.state.value.pairingPin
            val localIp = wifiIp
            if (!LocalAddress.isPrivateIpv4(localIp)) {
                MonitorBus.mutate { it.copy(error = "Не удалось определить локальный IPv4-адрес Wi-Fi") }
                stopSelf(); return
            }
            server = BabySignalServer(
                bindIp = localIp,
                pin = code,
                onConnected = { session, remote ->
                    pipe = session
                    MonitorBus.mutate { it.copy(remoteIp = remote, status = "Подключаем второй телефон…") }
                    engine?.preparePeer(false)
                },
                onMessage = { engine?.onSignal(it) },
                onDisconnected = { onSignalDisconnected() },
                onError = { message -> MonitorBus.mutate { it.copy(error = message) } }
            ).also { it.start() }
            beacon = BabyBeacon(localIp).also { it.start() }
            MonitorBus.mutate { it.copy(status = "Ожидание родительского телефона") }
        } else {
            connector = ParentConnector(
                ipOverride = ip,
                pin = pin,
                onConnected = { session, remote ->
                    pipe = session
                    MonitorBus.mutate { it.copy(remoteIp = remote, status = "Подключаем видео…") }
                    engine?.preparePeer(true)
                },
                onMessage = { engine?.onSignal(it) },
                onDisconnected = { onSignalDisconnected() },
                onStatus = { message -> MonitorBus.mutate { it.copy(status = message) } },
                onPairingFailed = {
                    MonitorBus.mutate { it.copy(error = "Код подключения не подошёл") }
                    stopSelf()
                },
                localBindIp = wifiIp,
                onBusy = {
                    MonitorBus.mutate { it.copy(error = "Камера уже подключена к другому телефону") }
                    stopSelf()
                }
            ).also { it.start() }
        }
        ticker = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleWithFixedDelay({ tick() }, 2, 2, TimeUnit.SECONDS)
        }
    }

    private fun onMediaConnection(connected: Boolean) {
        if (!running.get()) return
        if (connected) {
            val firstTransition = !iceConnected.getAndSet(true)
            if (role == Role.PARENT) {
                if (firstTransition) {
                    watchdog.connected(SystemClock.elapsedRealtime())
                    firstVideoFrameReceived.set(false)
                    alarmedForOutage = false
                    MonitorBus.mutate { it.copy(connected = false, status = "Связь есть. Загружаем видео…", error = "") }
                }
            } else {
                MonitorBus.mutate { it.copy(connected = true, status = "Связь установлена", error = "") }
            }
        } else {
            iceConnected.set(false)
            firstVideoFrameReceived.set(false)
            engine?.setTalking(false)
            MonitorBus.mutate { it.copy(connected = false, talking = false, status = "Проверяем связь…") }
        }
    }

    private fun onVideoFrameReceived() {
        if (!running.get() || role != Role.PARENT || !iceConnected.get()) return
        watchdog.videoFrame(SystemClock.elapsedRealtime())
        if (firstVideoFrameReceived.compareAndSet(false, true)) {
            MonitorBus.mutate { it.copy(connected = true, status = "Видео идёт", error = "") }
        }
    }

    private fun onSignalDisconnected() {
        if (!running.get()) return
        pipe = null
        iceConnected.set(false)
        firstVideoFrameReceived.set(false)
        engine?.setTalking(false)
        engine?.closePeer()
        qualityPending = true
        MonitorBus.mutate {
            it.copy(connected = false, talking = false, status = "Связь прервана. Подключаемся заново")
        }
    }

    private fun handleControl(message: String) {
        when {
            message == "LOCAL_SOUND" -> MonitorBus.mutate { it.copy(lastSoundAt = System.currentTimeMillis()) }
            role == Role.PARENT && message == "SOUND" -> {
                MonitorBus.mutate { it.copy(lastSoundAt = System.currentTimeMillis()) }
                alert("Громкий звук в комнате", "Камера зафиксировала продолжительный громкий звук", 400)
            }
            role == Role.PARENT && message == "PING" -> watchdog.heartbeat(SystemClock.elapsedRealtime())
            role == Role.PARENT && message.startsWith("LOCAL_RECEIVED:") -> {
                val measured = message.removePrefix("LOCAL_RECEIVED:")
                if (measured.length <= 60) MonitorBus.mutate { it.copy(actualVideo = measured) }
            }
            role == Role.BABY && message.startsWith("QUALITY:") -> {
                VideoQuality.fromWire(message.removePrefix("QUALITY:"))?.let { profile ->
                    engine?.setQuality(profile)
                    MonitorBus.mutate { it.copy(videoQuality = profile.name) }
                    engine?.sendControl("QUALITY_STATUS:${profile.name}")
                }
            }
            role == Role.PARENT && message.startsWith("QUALITY_STATUS:") -> {
                VideoQuality.fromWire(message.removePrefix("QUALITY_STATUS:"))?.let { profile ->
                    MonitorBus.mutate { it.copy(videoQuality = profile.name) }
                }
            }
            role == Role.BABY && message.startsWith("MUSIC:") -> {
                SleepCommand.parse(message)?.let { command ->
                    sleepPlayer.play(command.sound, command.minutes, command.volume)
                    MonitorBus.mutate { it.copy(musicSound = command.sound.name) }
                    engine?.sendControl("MUSIC_STATE:${command.sound.name}:${sleepPlayer.remainingSeconds()}")
                }
            }
            role == Role.PARENT && message.startsWith("MUSIC_STATE:") -> {
                val parts = message.split(':')
                if (parts.size == 3) {
                    val sound = SleepSound.fromWire(parts[1])
                    val remaining = parts[2].toIntOrNull()
                    if (sound != null && remaining != null && remaining in 0..3600) {
                        MonitorBus.mutate { it.copy(musicSound = sound.name, musicRemainingSeconds = remaining) }
                    }
                }
            }
            role == Role.PARENT && message.startsWith("BATTERY:") -> {
                val parts = message.split(':')
                if (parts.size == 3) {
                    val percent = parts[1].toIntOrNull()
                    if (percent != null && percent in 0..100) MonitorBus.mutate { it.copy(remoteBattery = percent, charging = parts[2] == "1") }
                }
            }
            role == Role.BABY && message == "NIGHT:1" -> updateNight(true)
            role == Role.BABY && message == "NIGHT:0" -> updateNight(false)
            role == Role.BABY && message == "TORCH:1" -> updateTorch(true)
            role == Role.BABY && message == "TORCH:0" -> updateTorch(false)
            role == Role.PARENT && message.startsWith("NIGHT_STATUS:") ->
                MonitorBus.mutate { it.copy(nightMode = message.endsWith("1")) }
            role == Role.PARENT && message.startsWith("TORCH_STATUS:") -> {
                val result = message.substringAfter(':')
                MonitorBus.mutate { it.copy(torchOn = result == "1", error = if (result == "UNAVAILABLE") "Фонарик недоступен во время видеосъёмки на этом телефоне" else "") }
            }
        }
    }

    private fun updateNight(value: Boolean) {
        engine?.setNight(value)
        MonitorBus.mutate { it.copy(nightMode = value) }
        engine?.sendControl("NIGHT_STATUS:${if (value) 1 else 0}")
    }

    private fun updateTorch(value: Boolean) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Exception) { null }
        var succeeded = false
        if (cameraId != null) {
            try { manager.setTorchMode(cameraId, value); succeeded = true }
            catch (_: Exception) { /* Some devices disallow torch when camera is in use. */ }
        }
        torchOn = value && succeeded
        MonitorBus.mutate { it.copy(torchOn = torchOn, error = if (succeeded) "" else "Фонарик занят камерой или не поддерживается") }
        engine?.sendControl("TORCH_STATUS:${if (succeeded) if (value) "1" else "0" else "UNAVAILABLE"}")
    }

    private fun tick() {
        if (!running.get()) return
        try {
            pipe?.send(WireMessage.Ping)
            if (role == Role.BABY) {
                engine?.sendControl("PING")
                val sound = sleepPlayer.currentSound()
                val remaining = sleepPlayer.remainingSeconds()
                MonitorBus.mutate { it.copy(musicSound = sound.name, musicRemainingSeconds = remaining) }
                engine?.sendControl("MUSIC_STATE:${sound.name}:$remaining")
                val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (battery != null) {
                    val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val charging = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
                        listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
                    if (level >= 0 && scale > 0) engine?.sendControl("BATTERY:${level * 100 / scale}:${if (charging) 1 else 0}")
                }
            } else if (role == Role.PARENT) {
                if (qualityPending && engine?.sendControl("QUALITY:${MonitorBus.state.value.videoQuality}") == true) {
                    qualityPending = false
                }
                val problem = watchdog.problem(SystemClock.elapsedRealtime())
                if (problem != null && !alarmedForOutage) {
                    alarmedForOutage = true
                    val (title, details) = when (problem) {
                        SessionWatchdog.Problem.NEVER_CONNECTED -> "Камера не подключилась" to "Не удалось получить первоначальное соединение с камерой"
                        SessionWatchdog.Problem.HEARTBEAT_MISSING -> "Связь с камерой потеряна" to "Пропали служебные сигналы. Проверьте Wi-Fi и телефон у ребёнка"
                        SessionWatchdog.Problem.VIDEO_MISSING -> "Видеосигнал пропал" to "Соединение активно, но кадры с камеры не поступают"
                    }
                    MonitorBus.mutate { it.copy(connected = false, status = title, error = details) }
                    alert(title, details, 401)
                    connector?.forceReconnect()
                }
            }
        } catch (e: Exception) { Log.w("MonitorService", "Periodic check failed", e) }
    }

    private fun setupNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MAIN_CHANNEL, "Работа видеоняни", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Тревоги видеоняни", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Звуки у ребёнка и потеря соединения"
            enableVibration(true)
        })
    }

    private fun notificationIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, com.homebabymonitor.ui.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun runningNotification(currentRole: Role): Notification = Notification.Builder(this, MAIN_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle(if (currentRole == Role.BABY) "Камера ребёнка работает" else "Родительский монитор работает")
        .setContentText("Подключение только через домашнюю сеть")
        .setOngoing(true)
        .setContentIntent(notificationIntent())
        .build()

    private fun alert(title: String, text: String, id: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(id, Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(notificationIntent())
                .build())
        }
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 85)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 750)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 1000)
        } catch (_: Exception) {}
    }

    private fun configureAudioOutput() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            formerAudioMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audio.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
            }
        } catch (e: Exception) { Log.w("MonitorService", "Could not route audio to speaker", e) }
    }

    private fun restoreAudioOutput() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            formerAudioMode?.let { audio.mode = it }
        } catch (_: Exception) {}
    }

    private fun acquirePowerLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeBabyMonitor:Session").apply { acquire() }
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HomeBabyMonitor:Wifi").apply { acquire() }
            // Some vendor Wi-Fi implementations filter incoming broadcasts without this lock.
            multicastLock = wifi.createMulticastLock("HomeBabyMonitor:Discovery").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w("MonitorService", "Could not acquire power locks", e) }
    }

    override fun onDestroy() {
        running.set(false)
        wifiRetry?.let(wifiHandler::removeCallbacks)
        wifiRetry = null
        mediaStarted = false
        sleepPlayer.stop()
        ticker?.shutdownNow(); ticker = null
        connector?.stop(); connector = null
        server?.stop(); server = null
        beacon?.stop(); beacon = null
        pipe?.close(); pipe = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        if (torchOn) try { updateTorch(false) } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        restoreAudioOutput()
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null) }
        catch (_: Exception) {}
        role = null
        MonitorBus.state.value = MonitorState(error = MonitorBus.state.value.error)
        super.onDestroy()
    }
}
