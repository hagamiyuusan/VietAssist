package com.vietassist.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun VinternPreparationStatus(state: VinternEngine.PreparationState) {
    val accent = Color(0xFFB5F5D2)
    val title = when (state) {
        VinternEngine.PreparationState.NotLoaded -> "AI đang chờ chuẩn bị"
        VinternEngine.PreparationState.Preparing -> "Đang chuẩn bị AI…"
        VinternEngine.PreparationState.Ready -> "AI đã sẵn sàng"
        is VinternEngine.PreparationState.Failed -> "AI chưa sẵn sàng"
    }
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state == VinternEngine.PreparationState.Preparing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
            }
            Text(title, color = accent, style = MaterialTheme.typography.bodyLarge)
        }
        when (state) {
            VinternEngine.PreparationState.NotLoaded,
            VinternEngine.PreparationState.Preparing ->
                Text("Đọc chữ nhanh vẫn dùng được trong lúc chờ.", color = accent,
                    style = MaterialTheme.typography.bodyMedium)
            is VinternEngine.PreparationState.Failed ->
                Text(state.message, color = Color(0xFFFFD6A5), style = MaterialTheme.typography.bodyMedium)
            VinternEngine.PreparationState.Ready -> Unit
        }
    }
}

@Composable
fun ReadingActions(aiReady: Boolean, busy: Boolean, onQuickRead: () -> Unit, onDetailedRead: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onQuickRead, enabled = !busy,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB5F5D2), contentColor = Color(0xFF071D17))
        ) { Text("Đọc chữ nhanh") }
        OutlinedButton(
            onClick = onDetailedRead, enabled = aiReady && !busy,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB5F5D2))
        ) { Text("Đọc chi tiết AI") }
    }
}
