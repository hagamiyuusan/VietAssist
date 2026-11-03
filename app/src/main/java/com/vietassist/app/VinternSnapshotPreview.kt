package com.vietassist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun VinternSnapshotPreview(image: ImageBitmap, busy: Boolean, onResumeCamera: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(image, "Ảnh đã chụp để phân tích", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xE6071D17)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFB5F5D2))
            Text(
                if (busy) "Đang phân tích ảnh đã chụp" else "Kết quả thuộc ảnh đã chụp",
                color = Color.White, style = MaterialTheme.typography.titleMedium
            )
            OutlinedButton(
                onClick = onResumeCamera, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB5F5D2))
            ) { Text("Trở lại camera") }
        }
    }
}
