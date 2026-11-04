package com.vietassist.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun VoiceModeSettings(alwaysListening: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text("Điều khiển bằng giọng nói", style = MaterialTheme.typography.titleMedium)
        for ((value, label) in listOf(false to "Nhấn để nói", true to "Luôn nghe")) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = alwaysListening == value, role = Role.RadioButton,
                        onClick = { onChange(value) }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = alwaysListening == value, onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFB5F5D2),
                        unselectedColor = Color(0xFFB5F5D2)))
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
        Text(if (alwaysListening)
            "Tự nghe lệnh trong ứng dụng và câu gọi khi chạy nền. Có thể tốn pin hơn. Cần quyền micro."
        else "Chỉ nhận lệnh khi bạn nhấn nút nói. Không nghe câu gọi khi chạy nền.",
            style = MaterialTheme.typography.bodyMedium)
    }
}
