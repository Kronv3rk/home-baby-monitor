package com.homebabymonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebabymonitor.MonitorBus
import com.homebabymonitor.MonitorService
import com.homebabymonitor.Role
import com.homebabymonitor.core.LocalAddress
import com.homebabymonitor.core.PairingCode
import com.homebabymonitor.core.PairingLink
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val barcodeScan = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val target = PairingLink.parse(result.contents)
            if (target == null) MonitorBus.mutate { it.copy(error = "Неверный QR-код") }
            else startSession(Role.PARENT, target.ip, target.code)
        }
    }
    private val cameraForQr = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openQrScanner()
        else MonitorBus.mutate { it.copy(error = "Для сканирования нужен доступ к камере") }
    }
    private fun openQrScanner() {
        barcodeScan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Наведите камеру на QR-код").setBeepEnabled(false).setOrientationLocked(false))
    }
    private fun scanQr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            openQrScanner()
        else cameraForQr.launch(Manifest.permission.CAMERA)
    }
    private var pendingStart: (() -> Unit)? = null
    private var pendingRequired: List<String> = emptyList()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val needed = pendingRequired
        if (needed.all { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) {
            finishPermissionFlow()
        } else {
            val missing = needed.filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
            val text = when {
                Manifest.permission.CAMERA in missing -> "Для камеры нужны доступ к съёмке и микрофону"
                Manifest.permission.RECORD_AUDIO in missing -> "Разрешите доступ к микрофону"
                else -> "Разрешите поиск устройств поблизости"
            }
            MonitorBus.mutate { state -> state.copy(error = text) }
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
            MonitorBus.mutate { it.copy(error = "Введите код из 20 символов с экрана камеры") }
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
            state.copy(error = "Разрешите уведомления, чтобы получать сигналы о потере связи")
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

    override fun onStop() {
        if (MonitorBus.state.value.role == Role.PARENT && MonitorBus.state.value.talking)
            sendCommand(MonitorService.ACTION_TALK, false)
        super.onStop()
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
                MonitorScreen(state,
                    startBaby = { startSession(Role.BABY, "", "") },
                    startParent = { ip, pin -> startSession(Role.PARENT, ip, pin) },
                    scanQr = { scanQr() },
                    stop = { sendCommand(MonitorService.ACTION_STOP) },
                    talk = { on -> sendCommand(MonitorService.ACTION_TALK, on) },
                    night = { on -> sendCommand(MonitorService.ACTION_NIGHT, on) },
                    torch = { on -> sendCommand(MonitorService.ACTION_TORCH, on) },
                    quality = { profile ->
                        startService(Intent(this, MonitorService::class.java).apply {
                            action = MonitorService.ACTION_QUALITY
                            putExtra(MonitorService.EXTRA_PROFILE, profile.name)
                        })
                    },
                    music = { sound, minutes, volume ->
                        startService(Intent(this, MonitorService::class.java).apply {
                            action = MonitorService.ACTION_MUSIC
                            putExtra(MonitorService.EXTRA_SOUND, sound.name)
                            putExtra(MonitorService.EXTRA_DURATION, minutes)
                            putExtra(MonitorService.EXTRA_VOLUME, volume)
                        })
                    }
                )
            }
        }
    }
}
