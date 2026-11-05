package com.vietassist.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun OcrSettingsControl(backend: OcrBackend, onSelect: (OcrBackend) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text("OCR mặc định", style = MaterialTheme.typography.titleMedium)
        for (option in OcrBackend.entries) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .selectable(backend == option, role = Role.RadioButton, onClick = { onSelect(option) }),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(backend == option, onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFB5F5D2), unselectedColor = Color(0xFFB5F5D2)))
                Text(option.label, Modifier.padding(start = 12.dp))
            }
        }
        Text(when (backend) {
            OcrBackend.ML_KIT -> "Đọc chữ bằng ML Kit, không cần tải model AI. Nói “đọc văn bản” để chụp và đọc."
            OcrBackend.VINTERN -> "Đọc chữ bằng Vintern. Cần tải và chuẩn bị model; không tự đổi bộ OCR khi lỗi."
            OcrBackend.LIGHTON -> "Đọc nguyên văn bằng LightOnOCR-3-0.8B. Cần tải model riêng; chất lượng tiếng Việt và tốc độ tùy ảnh, chưa được xác nhận trên tài liệu của bạn. Không tự đổi bộ OCR khi lỗi."
        })
    }
}
