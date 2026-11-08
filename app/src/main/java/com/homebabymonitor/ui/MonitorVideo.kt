package com.homebabymonitor.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.homebabymonitor.MonitorBus
import com.homebabymonitor.MonitorState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun VideoPane(
    state: MonitorState,
    cameraPreview: Boolean,
    modifier: Modifier,
    crop: Boolean = false,
    onExpand: (() -> Unit)? = null
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(MonitorBus.eglBase.eglBaseContext, null)
                    setEnableHardwareScaler(true)
                    setMirror(false)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    MonitorBus.attachRenderer(this, cameraPreview)
                }
            },
            update = { renderer ->
                renderer.setScalingType(
                    if (crop) RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    else RendererCommon.ScalingType.SCALE_ASPECT_FIT
                )
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { renderer ->
                MonitorBus.detachRenderer(renderer, cameraPreview)
                renderer.release()
            }
        )
        if ((!cameraPreview && !state.connected) ||
            (cameraPreview && state.actualVideo.isEmpty())) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!cameraPreview) {
                    Icon(Icons.Default.WifiOff, null, tint = muted, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(7.dp))
                }
                Text(if (cameraPreview) "Запускаем предпросмотр…" else "Нет видеосигнала",
                    color = Color.White, fontSize = 13.sp)
            }
        }
        if (cameraPreview) {
            Surface(modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color(0xD919222B), shape = RoundedCornerShape(50)) {
                Text("Кадр с этой камеры", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        } else {
            Surface(modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color(0xD919222B), shape = RoundedCornerShape(50)) {
                Text(if (state.connected) "В эфире" else "Нет связи",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        if (onExpand != null) {
            IconButton(
                onClick = onExpand,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Color(0xD919222B), RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Default.Fullscreen, "Развернуть видео на весь экран", tint = Color.White)
            }
        }
        if (!cameraPreview && state.actualVideo.isNotEmpty()) {
            val detail = if (state.networkVideo.isBlank()) state.actualVideo
                else "${state.actualVideo} · ${state.networkVideo}"
            Text(detail, color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .background(Color(0xAA182027), RoundedCornerShape(8.dp)).padding(6.dp))
        }
    }
}

@Composable
internal fun FullscreenMonitor(state: MonitorState, onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    var crop by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoPane(state, cameraPreview = false, modifier = Modifier.fillMaxSize(), crop = crop)
        Row(Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(onClick = { crop = !crop }, color = Color(0xD919222B),
                shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AspectRatio, null, tint = Color.White, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (crop) "Без обрезки" else "Заполнить экран",
                        color = Color.White, fontSize = 13.sp)
                }
            }
            IconButton(onClick = onClose,
                modifier = Modifier.background(Color(0xD919222B), RoundedCornerShape(12.dp))) {
                Icon(Icons.Default.CloseFullscreen, "Выйти из полноэкранного режима", tint = Color.White)
            }
        }
    }
}
