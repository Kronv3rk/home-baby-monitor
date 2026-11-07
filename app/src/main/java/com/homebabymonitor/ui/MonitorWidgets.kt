package com.homebabymonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homebabymonitor.MonitorState

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun StatusHeader(title: String, state: MonitorState) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (state.connected) teal else coral, CircleShape))
            Text(state.status, color = muted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun ActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), color = panel,
        shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = faint, shape = RoundedCornerShape(14.dp)) {
                Icon(icon, null, tint = teal, modifier = Modifier.padding(13.dp).size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                Text(subtitle, color = muted, fontSize = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = muted)
        }
    }
}

@Composable
internal fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = muted, fontSize = 13.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}
