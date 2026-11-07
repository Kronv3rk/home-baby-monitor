package com.homebabymonitor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import java.util.EnumMap

@Composable
internal fun PairingQr(text: String) {
    val bitmap = remember(text) {
        val pixels = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val colors = IntArray(512 * 512) { index ->
            if (pixels[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(colors, 512, 512, Bitmap.Config.ARGB_8888)
    }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR-код для подключения второго телефона",
        modifier = Modifier.size(244.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White).padding(10.dp))
}
