package com.homebabymonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homebabymonitor.MonitorState

@Composable internal fun Header(title: String, state: MonitorState) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.White)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (state.connected) Icons.Default.CheckCircle else Icons.Default.Sync, null, tint = if (state.connected) teal else muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(state.status, color = muted, fontSize = 13.sp)
    }
}

@Composable internal fun RoleCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String, onClick: () -> Unit) {
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

@Composable internal fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = muted)
        Text(value, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable internal fun InfoCard(modifier: Modifier, label: String, value: String) {
    Surface(modifier = modifier, color = panel, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text(label, color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable internal fun ControlButton(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), color = if (selected) Color(0xFF1D5152) else panel, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) teal else muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
