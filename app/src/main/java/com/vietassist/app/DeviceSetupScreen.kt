package com.vietassist.app

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun DeviceSetupScreen(
    requirements: LightOnRequirements,
    cameraGranted: Boolean,
    micGranted: Boolean,
    status: String,
    onCamera: () -> Unit,
    onMicrophone: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onReadStatus: () -> Unit,
    onContinue: () -> Unit,
    ttsStatus: String = "Chưa kiểm tra giọng đọc",
    ttsReady: Boolean = false,
    onTestVoice: () -> Unit = {},
    onTtsSettings: () -> Unit = {},
    lightOnReady: Boolean = false,
    lightOnDownloading: Boolean = false,
    lightOnProgress: Int = 0,
    lightOnPreparation: LightOnEngine.PreparationState = LightOnEngine.PreparationState.NotLoaded,
    onDownloadLightOn: () -> Unit = {},
    onPrepareLightOn: () -> Unit = {},
    ocrBackend: OcrBackend = OcrBackend.ML_KIT,
    onOcrBackendChange: (OcrBackend) -> Unit = {},
    alwaysListening: Boolean = false,
    onVoiceModeChange: (Boolean) -> Unit = {}
) {
    val background = Color(0xFF071D17)
    val accent = Color(0xFFB5F5D2)
    Surface(color = background, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Thiết lập LUMORA", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            Text("ML Kit là OCR mặc định, không cần model tải thêm. LightOnOCR chỉ cài và chạy khi GPU OpenCL đáp ứng.")
            val chip = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
            Text("${Build.MANUFACTURER} ${Build.MODEL} · $chip", color = accent)
            HorizontalDivider(color = accent.copy(alpha = 0.3f))
            SetupRequirement("Android và bộ xử lý", requirements.hardwareBlocker ?: 
                "Android ${Build.VERSION.RELEASE} · tiến trình 64 bit · có thư viện LightOnOCR. Chưa xác nhận tốc độ suy luận.")
            SetupRequirement("RAM", buildString {
                append("${gib(requirements.totalRam)} GB tổng · ${gib(requirements.availableRam)} GB đang khả dụng. ")
                append(when {
                    requirements.lowMemory -> requirements.inferenceBlocker
                    requirements.ramWarning -> "Nên đóng bớt ứng dụng trước khi chạy LightOn."
                    else -> "Đạt mức RAM khuyến nghị."
                })
                append(" Khuyến nghị của ứng dụng: 4 GB tổng, 2 GB khả dụng; chưa phải ngưỡng đã đo hiệu năng.")
            })
            SetupRequirement("Dung lượng", "${gib(requirements.freeStorage)} GB trống. " +
                if (lightOnReady) "Model đã có trên máy."
                else "Cần thêm ${(requirements.requiredStorage + 1048575) / 1048576} MB để tải tiếp và dự phòng.")
            SetupRequirement("GPU OpenCL", requirements.hardwareBlocker ?: "Đã tìm thấy GPU OpenCL. LightOnOCR chạy phần ảnh và model ngôn ngữ trên GPU; không chuyển sang CPU.")
            HorizontalDivider(color = accent.copy(alpha = 0.3f))
            SetupRequirement("Camera", if (cameraGranted) "Đã cấp quyền." else "Cần để nhận diện và đọc hình ảnh.")
            if (!cameraGranted) SetupButton("Cấp quyền camera", onCamera)
            VoiceModeSettings(alwaysListening, onVoiceModeChange)
            OcrSettingsControl(ocrBackend, onOcrBackendChange)
            if (ocrBackend == OcrBackend.LIGHTON) {
                SetupRequirement("LightOnOCR-3-0.8B · ${LightOnModelManager.totalBytes / 1048576} MB", when {
                    lightOnDownloading -> "Đang tải $lightOnProgress%. Có thể tải tiếp nếu bị gián đoạn."
                    lightOnReady -> "Đã có đủ file LightOnOCR."
                    else -> "Chưa tải model OCR. Dùng Wi-Fi để tải."
                })
                if (lightOnReady) {
                    LightOnPreparationStatus(lightOnPreparation)
                    SetupButton("Chuẩn bị LightOnOCR", onPrepareLightOn, enabled = requirements.inferenceBlocker == null && lightOnPreparation != LightOnEngine.PreparationState.Preparing)
                } else SetupButton("Tải LightOnOCR", onDownloadLightOn, enabled = !lightOnDownloading && requirements.downloadBlocker == null)
                if (lightOnDownloading) LinearProgressIndicator(progress = { lightOnProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            SetupRequirement("Giọng đọc tiếng Việt", ttsStatus)
            SetupButton("Nghe thử tiếng Việt", onTestVoice, enabled = ttsReady)
            TextButton(onClick = onTtsSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cài đặt giọng đọc", color = accent)
            }
            SetupRequirement("Micro", if (micGranted) "Đã cấp quyền." else "Tùy chọn: cần để điều khiển bằng giọng nói. Vẫn dùng được nút bấm.")
            if (!micGranted) SetupButton("Cấp quyền micro", onMicrophone)
            if (!cameraGranted || !micGranted) {
                TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Mở cài đặt quyền ứng dụng", color = accent)
                }
            }
            HorizontalDivider(color = accent.copy(alpha = 0.3f))
            if (status.isNotBlank()) Text(status, color = accent)
            val blocker = requirements.hardwareBlocker
            if (blocker != null) Text(blocker, color = Color(0xFFFFD6A5))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Kiểm tra lại", color = accent)
                }
                TextButton(onClick = onReadStatus, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Đọc tình trạng", color = accent)
                }
            }
            SetupButton(if (blocker == null) "Tiếp tục sử dụng" else "Tiếp tục với nhận diện vật thể", onContinue)
            Text("Bạn có thể mở lại màn hình này bằng nút Thiết lập trên màn hình chính.", color = accent)
        }
    }
}

@Composable
private fun SetupRequirement(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(detail, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SetupButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB5F5D2), contentColor = Color(0xFF071D17))
    ) { Text(text, modifier = Modifier.padding(vertical = 4.dp)) }
}

private fun gib(bytes: Long): String = String.format(Locale.forLanguageTag("vi-VN"), "%.1f", bytes / (1L shl 30).toDouble())
