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
    requirements: VinternRequirements,
    cameraGranted: Boolean,
    micGranted: Boolean,
    modelReady: Boolean,
    downloading: Boolean,
    progress: Int,
    status: String,
    onCamera: () -> Unit,
    onMicrophone: () -> Unit,
    onSettings: () -> Unit,
    onDownload: () -> Unit,
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
    lightOnPreparation: VinternEngine.PreparationState = VinternEngine.PreparationState.NotLoaded,
    onDownloadLightOn: () -> Unit = {},
    onPrepareLightOn: () -> Unit = {},
    ocrBackend: OcrBackend = OcrBackend.ML_KIT,
    onOcrBackendChange: (OcrBackend) -> Unit = {},
    alwaysListening: Boolean = false,
    onVoiceModeChange: (Boolean) -> Unit = {},
    preparationState: VinternEngine.PreparationState = VinternEngine.PreparationState.NotLoaded
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
            Text("Kiểm tra máy và chuẩn bị AI mô tả, đọc chi tiết. Camera và nhận diện vật thể vẫn dùng được khi chưa có model.")
            val chip = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
            Text("${Build.MANUFACTURER} ${Build.MODEL} · $chip", color = accent)
            HorizontalDivider(color = accent.copy(alpha = 0.3f))
            SetupRequirement("Android và bộ xử lý", requirements.hardwareBlocker ?: 
                "Android ${Build.VERSION.RELEASE} · tiến trình 64 bit · có thư viện Vintern. Chưa xác nhận tốc độ suy luận.")
            SetupRequirement("RAM", buildString {
                append("${gib(requirements.totalRam)} GB tổng · ${gib(requirements.availableRam)} GB đang khả dụng. ")
                append(when {
                    requirements.lowMemory -> requirements.inferenceBlocker
                    requirements.ramWarning -> "Nên đóng bớt ứng dụng trước khi chạy Vintern."
                    else -> "Đạt mức RAM khuyến nghị."
                })
                append(" Khuyến nghị của ứng dụng: 4 GB tổng, 2 GB khả dụng; chưa phải ngưỡng đã đo hiệu năng.")
            })
            SetupRequirement("Dung lượng", "${gib(requirements.freeStorage)} GB trống. " +
                if (modelReady) "Model đã có trên máy."
                else "Cần thêm ${(requirements.requiredStorage + 1048575) / 1048576} MB để tải tiếp và dự phòng.")
            SetupRequirement("Model đang dùng", "Vintern-1B-v3.5 · dùng để mô tả cảnh. OCR được chọn riêng bên dưới.")
            SetupRequirement("Cách chạy AI", "Vintern-1B-v3.5 dùng GPU OpenCL để xử lý ảnh; phần sinh câu trả lời dùng CPU hoặc GPU tùy chip. Cần Android 9 và driver OpenCL của hãng điện thoại; tương thích được kiểm tra khi chạy AI.")
            HorizontalDivider(color = accent.copy(alpha = 0.3f))
            SetupRequirement("Camera", if (cameraGranted) "Đã cấp quyền." else "Cần để nhận diện và đọc hình ảnh.")
            if (!cameraGranted) SetupButton("Cấp quyền camera", onCamera)
            VoiceModeSettings(alwaysListening, onVoiceModeChange)
            OcrSettingsControl(ocrBackend, onOcrBackendChange)
            if (ocrBackend == OcrBackend.LIGHTON) {
                SetupRequirement("LightOnOCR-3-0.8B · ${LightOnModelManager.totalBytes / 1048576} MB", when {
                    lightOnDownloading -> "Đang tải $lightOnProgress%. Có thể tải tiếp nếu bị gián đoạn."
                    lightOnReady -> "Đã có đủ file LightOnOCR. Vintern vẫn dùng riêng cho mô tả cảnh."
                    else -> "Chưa tải model OCR. Dùng Wi-Fi để tải; không cần tải lại Vintern."
                })
                if (lightOnReady) {
                    VinternPreparationStatus(lightOnPreparation)
                    SetupButton("Chuẩn bị LightOnOCR", onPrepareLightOn, enabled = lightOnPreparation != VinternEngine.PreparationState.Preparing)
                } else SetupButton("Tải LightOnOCR", onDownloadLightOn, enabled = !lightOnDownloading && requirements.hardwareBlocker == null)
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
            SetupRequirement("Mô hình AI · ${VinternModelManager.totalBytes / 1048576} MB", when {
                downloading -> "Đang tải $progress%. Giữ ứng dụng mở để hoàn tất."
                modelReady -> "Đã tải đủ hai file model. Không cần tải lại."
                else -> "Chưa sẵn sàng. Nên tải bằng Wi-Fi; có thể tải tiếp nếu bị gián đoạn."
            })
            if (modelReady) VinternPreparationStatus(preparationState)
            if (downloading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status, color = accent)
            val blocker = if (modelReady) requirements.inferenceBlocker else requirements.downloadBlocker
            if (blocker != null) Text(blocker, color = Color(0xFFFFD6A5))
            if (!modelReady) SetupButton(
                "Tải mô hình AI", onDownload, enabled = !downloading && blocker == null
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Kiểm tra lại", color = accent)
                }
                TextButton(onClick = onReadStatus, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Đọc tình trạng", color = accent)
                }
            }
            SetupButton(if (modelReady && blocker == null) "Tiếp tục sử dụng" else "Tiếp tục với nhận diện vật thể", onContinue)
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
