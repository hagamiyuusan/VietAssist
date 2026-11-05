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
fun LightOnPreparationStatus(state: LightOnEngine.PreparationState) {
    val accent = Color(0xFFB5F5D2)
    val title = when (state) {
        LightOnEngine.PreparationState.NotLoaded -> "AI đang chờ chuẩn bị"
        LightOnEngine.PreparationState.Preparing -> "Đang chuẩn bị AI…"
        LightOnEngine.PreparationState.Ready -> "AI đã sẵn sàng"
        is LightOnEngine.PreparationState.Failed -> "AI chưa sẵn sàng"
    }
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state == LightOnEngine.PreparationState.Preparing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
            }
            Text(title, color = accent, style = MaterialTheme.typography.bodyLarge)
        }
        when (state) {
            LightOnEngine.PreparationState.NotLoaded,
            LightOnEngine.PreparationState.Preparing ->
                Text("Camera và khung nhận diện vẫn dùng được trong lúc chờ.", color = accent,
                    style = MaterialTheme.typography.bodyMedium)
            is LightOnEngine.PreparationState.Failed ->
                Text(state.message, color = Color(0xFFFFD6A5), style = MaterialTheme.typography.bodyMedium)
            LightOnEngine.PreparationState.Ready -> Unit
        }
    }
}
