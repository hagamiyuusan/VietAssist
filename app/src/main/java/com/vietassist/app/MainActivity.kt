package com.vietassist.app

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Build
import java.util.Locale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.atomic.AtomicLong
import com.vietassist.app.ui.theme.VietAssistTheme
import java.util.concurrent.Executors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Tăng tương phản nhẹ trên BẢN SAO dành riêng cho OCR.
// Không thay đổi bitmap đầu vào của EfficientDet.
private fun createOcrContrastBitmap(source: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(
        source.width,
        source.height,
        Bitmap.Config.ARGB_8888
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, -8f,
                    0f, 1.08f, 0f, 0f, -8f,
                    0f, 0f, 1.08f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    Canvas(output).drawBitmap(source, 0f, 0f, paint)
    return output
}

class MainActivity : ComponentActivity() {
    var wakeRequest by mutableIntStateOf(0)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OnlineVietnameseDictionary.initialize(this)
        if (intent?.getBooleanExtra("lumora_wake_detected", false) == true &&
            intent?.getBooleanExtra("lumora_wake_reply_done", false) != true
        ) {
            wakeRequest++
        }

        setContent {
            VietAssistTheme {
                VietAssistScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("lumora_wake_detected", false) &&
            !intent.getBooleanExtra("lumora_wake_reply_done", false)
        ) {
            wakeRequest++
        }
    }

    override fun onStart() {
        super.onStart()
        // Dịch vụ được khởi động khi Activity đang hiển thị, đáp ứng hạn chế
        // Android đối với foreground service sử dụng micro.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLumoraVoiceService(LumoraVoiceService.ACTION_INIT, foregroundStart = true)
        }
    }

    override fun onStop() {
        // VoiceCommandListener sẽ tự dừng ở ON_PAUSE. Chỉ sau khi Activity rời
        // màn hình, dịch vụ mới được phép nghe cụm đánh thức trong nền.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLumoraVoiceService(LumoraVoiceService.ACTION_LISTEN_BACKGROUND)
        }
        super.onStop()
    }

    private fun startLumoraVoiceService(action: String, foregroundStart: Boolean = false) {
        val serviceIntent = Intent(this, LumoraVoiceService::class.java).setAction(action)
        try {
            if (foregroundStart) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (error: Exception) {
            Log.e("LUMORA_BG_VOICE", "Không thể gửi lệnh cho dịch vụ giọng nói", error)
        }
    }
}

@Composable
fun VietAssistScreen() {
    val context = LocalContext.current
    val hostActivity = context as? MainActivity
    var handledWakeRequest by remember { mutableIntStateOf(0) }
    // Lưu trạng thái hướng dẫn để không đọc lại mỗi lần mở ứng dụng.
    val onboardingPrefs = remember(context) {
        context.getSharedPreferences("lumora_preferences", Context.MODE_PRIVATE)
    }
    var showDeviceSetup by rememberSaveable {
        mutableStateOf(!onboardingPrefs.getBoolean("device_setup_v1_seen", false))
    }
    var deviceRequirements by remember { mutableStateOf(VinternRequirements.read(context)) }
    var onboardingAlreadyCompleted by remember {
        mutableStateOf(onboardingPrefs.getBoolean("onboarding_v2_completed", false))
    }

    var ttsEngine by remember {
        mutableStateOf<TextToSpeech?>(null)
    }

    var ttsReady by remember {
        mutableStateOf(false)
    }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null

        engine = TextToSpeech(context) { status ->
            val currentEngine = engine

            if (status == TextToSpeech.SUCCESS && currentEngine != null) {
                val vietnameseLocale = Locale.forLanguageTag("vi-VN")

                val languageStatus =
                    currentEngine.setLanguage(vietnameseLocale)

                currentEngine.setSpeechRate(0.9f)
                currentEngine.setPitch(1.0f)

                val currentVoice = currentEngine.voice

                Log.d(
                    "LUMORA_TTS",
                    "Language status: $languageStatus"
                )

                Log.d(
                    "LUMORA_TTS",
                    "Voice name: ${currentVoice?.name}"
                )

                Log.d(
                    "LUMORA_TTS",
                    "Voice locale: ${currentVoice?.locale}"
                )

                ttsReady =
                    languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED

                if (!ttsReady) {
                    Log.e(
                        "LUMORA_TTS",
                        "Chưa hỗ trợ tiếng Việt. Mã ngôn ngữ: $languageStatus"
                    )
                }
            } else {
                ttsReady = false
                Log.e(
                    "LUMORA_TTS",
                    "Khởi tạo TextToSpeech thất bại: $status"
                )
            }
        }

        ttsEngine = engine

        onDispose {
            engine?.stop()
            engine?.shutdown()
            ttsEngine = null
            ttsReady = false
        }
    }

    var cameraStarted by remember {
        mutableStateOf(false)
    }

    var readingMode by remember { mutableStateOf(false) }
    var onboardingStarted by remember { mutableStateOf(onboardingAlreadyCompleted) }
    var onboardingWaiting by remember { mutableStateOf(false) }
    var onboardingSpeaking by remember { mutableStateOf(false) }
    var onboardingDone by remember { mutableStateOf(onboardingAlreadyCompleted) }
    var micPermissionRequestPending by remember { mutableStateOf(false) }
    // Mỗi lần tăng biến này, OCR sẽ nhận diện lại văn bản.
    var ocrCaptureRequest by remember { mutableStateOf(0) }

    // Vintern (AI đọc ảnh chạy trên máy): chụp một khung hình rồi mô tả hoặc đọc chi tiết.
    val coroutineScope = rememberCoroutineScope()
    var vinternCaptureRequest by remember { mutableIntStateOf(0) }
    val vinternRun = remember { AtomicLong(0) }
    val vinternCancellation = remember { AtomicLong(0) }
    var vinternSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            vinternRun.incrementAndGet()
            VinternEngine.cancel(vinternCancellation)
        }
    }
    var vinternMode by remember { mutableStateOf(VinternEngine.Mode.DESCRIBE) }
    var vinternBusy by remember { mutableStateOf(false) }
    var vinternAwaitingFrame by remember { mutableStateOf(false) }
    var vinternCancelled by remember { mutableStateOf(false) }
    var vinternStatus by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(VinternModelManager.isReady(context)) }
    val preparationState by VinternEngine.preparationState.collectAsState()
    val aiReady = preparationState == VinternEngine.PreparationState.Ready
    LaunchedEffect(modelReady) {
        if (modelReady) VinternEngine.prepare(context)
    }
    val downloadState by VinternModelManager.downloadState.collectAsState()
    val modelDownloading = downloadState.downloading
    val modelDownloadPercent = downloadState.percent
    var modelDownloadJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(downloadState) {
        if (!downloadState.downloading) modelReady = VinternModelManager.isReady(context)
    }
    val downloadGeneration = remember { AtomicLong(0) }
    DisposableEffect(Unit) {
        onDispose { downloadGeneration.incrementAndGet() }
    }

    var detectedText by remember {
        mutableStateOf("Đang chờ phân tích hình ảnh")
    }

    val speechText = remember(detectedText) {
        translateDetectionForSpeech(detectedText)
    }

    var lastSpokenText by remember {
        mutableStateOf("")
    }

    var lastSpokenAt by remember {
        mutableStateOf(0L)
    }

    // Lưu riêng nội dung OCR cuối cùng đã đọc.
    var lastSpokenOcrText by remember {
        mutableStateOf("")
    }

    // Trạng thái TTS dùng chung. Mỗi lượt đọc có ID riêng để callback cũ
    // không thể mở khóa nhầm khi một câu mới đã bắt đầu.
    var ttsSpeaking by remember { mutableStateOf(false) }
    var activeUtteranceId by remember { mutableStateOf<String?>(null) }
    var ignoreVoiceEcho by remember { mutableStateOf(false) }
    var speechCompletionTick by remember { mutableStateOf(0) }

    fun speakMessage(
        text: String,
        queueMode: Int,
        params: Bundle?,
        utteranceId: String
    ): Int {
        val engine = ttsEngine ?: return TextToSpeech.ERROR
        // ID duy nhất, kể cả khi cùng một lệnh được phát nhiều lần.
        val uniqueUtteranceId = "${utteranceId}__run_${System.nanoTime()}"
        activeUtteranceId = uniqueUtteranceId
        ttsSpeaking = true
        ignoreVoiceEcho = true

        val result = engine.speak(text, queueMode, params, uniqueUtteranceId)
        if (result != TextToSpeech.SUCCESS && activeUtteranceId == uniqueUtteranceId) {
            activeUtteranceId = null
            ttsSpeaking = false
            ignoreVoiceEcho = false
        }
        return result
    }

    // Phần hướng dẫn dùng ID riêng để chỉ chuyển bước khi TTS nói xong.
    fun askOnboardingQuestion() {
        onboardingStarted = true
        onboardingDone = false
        onboardingWaiting = false
        onboardingSpeaking = true
        val result = speakMessage(
            "Bạn có cần tôi hướng dẫn cách sử dụng không? " +
                    "Hãy nói cần hướng dẫn hoặc bỏ qua.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lumora_onboarding_question"
        )
        if (result != TextToSpeech.SUCCESS) {
            onboardingSpeaking = false
            onboardingWaiting = true
        }
    }

    fun startOnboardingInstructions() {
        onboardingWaiting = false
        onboardingSpeaking = true
        val result = speakMessage(
            "Tôi sẽ hướng dẫn ngắn gọn. " +
                    "Nói bắt đầu quan sát để nhận biết vật thể xung quanh. " +
                    "Nói đọc văn bản để đọc sách hoặc tài liệu. " +
                    "Nói nhận diện lại để đọc phần chữ khác. " +
                    "Nói mô tả xung quanh để AI mô tả cảnh trước mặt, " +
                    "hoặc đọc chi tiết để AI đọc kỹ tài liệu. " +
                    "Lần đầu dùng hai lệnh này, hãy nói tải mô hình khi có Wi-Fi. " +
                    "Nói tạm dừng quan sát để dừng camera. " +
                    "Bạn cũng có thể dùng các nút lớn trên màn hình. " +
                    "Ứng dụng chỉ hỗ trợ nhận biết, không bảo đảm đường đi an toàn.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lumora_onboarding_instructions"
        )
        if (result != TextToSpeech.SUCCESS) {
            onboardingSpeaking = false
            onboardingDone = true
            onboardingAlreadyCompleted = true
            onboardingPrefs.edit().putBoolean("onboarding_v2_completed", true).apply()
        }
    }

    fun skipOnboarding() {
        onboardingWaiting = false
        onboardingSpeaking = true
        val result = speakMessage(
            "Được rồi. Bạn có thể nói bắt đầu quan sát hoặc đọc văn bản khi cần.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lumora_onboarding_skipped"
        )
        if (result != TextToSpeech.SUCCESS) {
            onboardingSpeaking = false
            onboardingDone = true
            onboardingAlreadyCompleted = true
            onboardingPrefs.edit().putBoolean("onboarding_v2_completed", true).apply()
        }
    }

    // Theo dõi toàn bộ lượt đọc, chặn lệnh bị micro thu lại từ chính giọng LUMORA.
    DisposableEffect(ttsEngine) {
        val engine = ttsEngine
        if (engine != null) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                private fun finishUtterance(utteranceId: String?, failed: Boolean = false) {
                    Handler(Looper.getMainLooper()).post {
                        // QUEUE_FLUSH có thể phát callback của câu cũ sau khi câu mới
                        // đã được xếp. Chỉ callback của lượt hiện hành mới được đổi trạng thái.
                        if (utteranceId == null || utteranceId != activeUtteranceId) {
                            Log.d("LUMORA_TTS", "Bỏ qua callback TTS cũ: $utteranceId")
                            return@post
                        }

                        activeUtteranceId = null
                        ttsSpeaking = false
                        speechCompletionTick++
                        ignoreVoiceEcho = true
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!ttsSpeaking && activeUtteranceId == null) {
                                ignoreVoiceEcho = false
                            }
                        }, 650L)

                        val eventKey = utteranceId.substringBefore("__run_")
                        when (eventKey) {
                            "lumora_onboarding_welcome" -> {
                                micPermissionRequestPending = true
                            }
                            "lumora_mic_denied" -> {
                                onboardingSpeaking = false
                                onboardingWaiting = true
                            }
                            "lumora_onboarding_question" -> {
                                onboardingSpeaking = false
                                onboardingWaiting = true
                            }
                            "lumora_onboarding_instructions",
                            "lumora_onboarding_skipped" -> {
                                onboardingSpeaking = false
                                onboardingWaiting = false
                                onboardingDone = true
                                onboardingAlreadyCompleted = true
                                onboardingPrefs.edit()
                                    .putBoolean("onboarding_v2_completed", true)
                                    .apply()
                            }
                            "lumora_onboarding_retry" -> {
                                onboardingSpeaking = false
                                onboardingWaiting = true
                            }
                        }

                        if (failed && eventKey.startsWith("lumora_onboarding_")) {
                            onboardingSpeaking = false
                            onboardingWaiting = true
                        }
                    }
                }

                override fun onStart(utteranceId: String?) {
                    Handler(Looper.getMainLooper()).post {
                        if (utteranceId == activeUtteranceId) {
                            ttsSpeaking = true
                            ignoreVoiceEcho = true
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    finishUtterance(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finishUtterance(utteranceId, failed = interrupted)
                }

                override fun onError(utteranceId: String?) {
                    finishUtterance(utteranceId, failed = true)
                }
            })
        }
        onDispose {
            engine?.setOnUtteranceProgressListener(null)
        }
    }

    // Chỉ lần đầu tự giới thiệu. Sau đó im lặng khi mở bình thường;
    // chỉ phản hồi khi người dùng gọi trợ lý.
    LaunchedEffect(ttsReady, hostActivity?.wakeRequest, showDeviceSetup) {
        if (showDeviceSetup) return@LaunchedEffect
        if (!ttsReady) return@LaunchedEffect

        if (!onboardingAlreadyCompleted && !onboardingStarted) {
            onboardingStarted = true
            onboardingSpeaking = true
            val result = speakMessage(
                "Xin chào. Tôi là trợ lý thị giác của bạn, hỗ trợ nhận biết một số vật thể xung quanh " +
                        "và đọc chữ trên sách, tài liệu, nhãn sản phẩm. " +
                        "Để điều khiển bằng giọng nói, ứng dụng cần quyền sử dụng micro. " +
                        "Sau lời chào, điện thoại sẽ hỏi bạn có cho phép hay không.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "lumora_onboarding_welcome"
            )
            if (result != TextToSpeech.SUCCESS) {
                onboardingSpeaking = false
                micPermissionRequestPending = true
            }
        } else {
            val requestedWake = hostActivity?.wakeRequest ?: 0
            if (onboardingAlreadyCompleted && requestedWake > handledWakeRequest) {
                handledWakeRequest = requestedWake
                speakMessage(
                    "Có tôi đây.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_wake_reply"
                )
            }
        }
    }

    LaunchedEffect(
        speechText,
        ttsReady,
        readingMode,
        onboardingSpeaking,
        onboardingWaiting,
        speechCompletionTick,
        vinternBusy,
        vinternSnapshot,
        showDeviceSetup
    ) {
        val message = speechText ?: return@LaunchedEffect
        if (showDeviceSetup || !ttsReady || message.isBlank()) return@LaunchedEffect
        // Trong lúc AI phân tích ảnh, không chen thông báo vật thể vào.
        if (vinternBusy || vinternSnapshot != null) return@LaunchedEffect

        // Debounce các cập nhật khung hình liên tiếp; chỉ lấy kết quả mới nhất.
        kotlinx.coroutines.delay(220)

        val ocrPrefix = "Nội dung nhận diện được như sau: "
        val isOcrMessage = message.startsWith(ocrPrefix)
        val isOcrStatus = message.startsWith("OCR_STATUS:")
        val currentOcrText = if (isOcrMessage) message.removePrefix(ocrPrefix).trim() else ""
        val spokenOcrStatus = if (isOcrStatus) message.removePrefix("OCR_STATUS:").trim() else ""

        // Giữ hai chế độ độc lập, không phát vật thể khi đang đọc tài liệu và ngược lại.
        if (readingMode && !isOcrMessage && !isOcrStatus) return@LaunchedEffect
        if (!readingMode && (isOcrMessage || isOcrStatus)) return@LaunchedEffect
        if (onboardingSpeaking || onboardingWaiting) return@LaunchedEffect

        if (isOcrMessage) {
            if (currentOcrText.isBlank() || currentOcrText == lastSpokenOcrText) {
                return@LaunchedEffect
            }
        } else if (isOcrStatus) {
            if (spokenOcrStatus.isBlank() || message == lastSpokenText) return@LaunchedEffect
        } else if (message == lastSpokenText) {
            return@LaunchedEffect
        }

        // Không bao giờ dùng QUEUE_FLUSH để cắt ngang một câu tự động đang phát.
        // Một số TTS báo isSpeaking chậm hơn callback, nên chờ ngắn rồi kiểm tra lại.
        if (ttsSpeaking || ttsEngine?.isSpeaking == true) {
            kotlinx.coroutines.delay(180)
            if (ttsSpeaking || ttsEngine?.isSpeaking == true) return@LaunchedEffect
        }

        // Hạn chế đọc vật thể liên tục khi camera dao động, nhưng vẫn phản hồi nhanh.
        if (!isOcrMessage && !isOcrStatus) {
            val elapsed = System.currentTimeMillis() - lastSpokenAt
            val remaining = 1800L - elapsed
            if (remaining > 0L) kotlinx.coroutines.delay(remaining)
        }

        if (ttsSpeaking || ttsEngine?.isSpeaking == true) {
            kotlinx.coroutines.delay(180)
            if (ttsSpeaking || ttsEngine?.isSpeaking == true) return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        val result = speakMessage(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lumora_auto_${now}"
        )

        if (result == TextToSpeech.SUCCESS) {
            lastSpokenText = message
            if (isOcrMessage) lastSpokenOcrText = currentOcrText
            if (!isOcrMessage) lastSpokenText = message
            lastSpokenAt = now
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasPermission = granted
            cameraStarted = granted && !showDeviceSetup

            detectedText = if (granted) {
                "Đang khởi động nhận diện AI..."
            } else {
                "Bạn cần cấp quyền camera để sử dụng LUMORA"
            }
        }

    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Lần đầu: giới thiệu xong thì đọc hướng dẫn đầy đủ luôn.
            // Những lần sau không tự phát lại lời chào hay hướng dẫn.
            if (!showDeviceSetup && !onboardingAlreadyCompleted) startOnboardingInstructions()
        }

    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            micPermissionGranted = granted
            if (granted) {
                // Khởi động foreground service ngay khi người dùng cấp micro,
                // khi Activity vẫn đang hiển thị; không đợi tới lúc ra nền.
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, LumoraVoiceService::class.java)
                            .setAction(LumoraVoiceService.ACTION_INIT)
                    )
                } catch (error: Exception) {
                    Log.e("LUMORA_BG_VOICE", "Không thể khởi động nghe nền", error)
                }
                if (!showDeviceSetup && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!showDeviceSetup && !onboardingAlreadyCompleted) {
                    startOnboardingInstructions()
                }
            } else if (!showDeviceSetup) {
                detectedText = "Chưa cấp quyền micro. Có thể dùng các nút trên màn hình."
                onboardingSpeaking = true
                onboardingWaiting = false
                val result = speakMessage(
                    "Bạn chưa cấp quyền micro nên điều khiển bằng giọng nói chưa hoạt động. " +
                            "Tôi vẫn hướng dẫn cách dùng. Nói bắt đầu quan sát để nhận biết vật thể. " +
                            "Nói đọc văn bản để đọc sách hoặc tài liệu. Nói nhận diện lại để đọc phần chữ khác. " +
                            "Nói mô tả xung quanh hoặc đọc chi tiết để dùng AI mô tả và đọc kỹ. " +
                            "Bạn vẫn có thể dùng các nút lớn trên màn hình. " +
                            "Ứng dụng chỉ hỗ trợ nhận biết, không bảo đảm đường đi an toàn.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_onboarding_instructions"
                )
                if (result != TextToSpeech.SUCCESS) {
                    onboardingSpeaking = false
                    onboardingDone = true
                    onboardingAlreadyCompleted = true
                    onboardingPrefs.edit().putBoolean("onboarding_v2_completed", true).apply()
                }
            }
        }

    LaunchedEffect(micPermissionRequestPending, micPermissionGranted) {
        if (micPermissionRequestPending) {
            micPermissionRequestPending = false
            if (micPermissionGranted) {
                if (!showDeviceSetup && !onboardingAlreadyCompleted) startOnboardingInstructions()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val setupLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(setupLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                deviceRequirements = VinternRequirements.read(context)
                modelReady = VinternModelManager.isReady(context)
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                micPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
        }
        setupLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { setupLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(showDeviceSetup) {
        while (showDeviceSetup) {
            deviceRequirements = VinternRequirements.read(context)
            modelReady = VinternModelManager.isReady(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    val modelSizeText = "khoảng ${VinternModelManager.totalBytes / (1024 * 1024)} megabyte"

    fun startModelDownload() {
        deviceRequirements = VinternRequirements.read(context)
        val blocked = deviceRequirements.downloadBlocker
        if (blocked != null) {
            vinternStatus = blocked
            showDeviceSetup = true
            speakMessage(blocked, TextToSpeech.QUEUE_FLUSH, null, "lumora_requirements")
            return
        }
        if (modelReady) {
            speakMessage(
                when (val state = preparationState) {
                    VinternEngine.PreparationState.Ready -> "AI đã sẵn sàng. Bạn có thể nói mô tả xung quanh hoặc đọc chi tiết."
                    is VinternEngine.PreparationState.Failed -> state.message
                    else -> "Mô hình đã tải xong. AI đang chuẩn bị; bạn vẫn có thể đọc chữ nhanh."
                },
                TextToSpeech.QUEUE_FLUSH,
                null,
                "lumora_model_ready"
            )
            return
        }
        if (modelDownloading || modelDownloadJob?.isActive == true) {
            speakMessage(
                "Đang tải mô hình AI, đã được $modelDownloadPercent phần trăm.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "lumora_model_progress"
            )
            return
        }
        vinternStatus = ""
        speakMessage(
            "Bắt đầu tải mô hình AI, $modelSizeText. Nên dùng Wi-Fi. Tôi sẽ báo khi tải xong.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lumora_model_download"
        )
        val generation = downloadGeneration.incrementAndGet()
        modelDownloadJob = coroutineScope.launch {
            val succeeded = withContext(Dispatchers.IO) {
                try {
                    VinternModelManager.download(context) { }
                    true
                } catch (error: Exception) {
                    Log.e("LUMORA_VINTERN", "Tải mô hình thất bại", error)
                    false
                }
            }
            if (downloadGeneration.get() != generation) return@launch
            modelReady = VinternModelManager.isReady(context)
            if (succeeded && modelReady) {
                vinternStatus = "Đã tải xong. Đang chuẩn bị AI…"
                VinternEngine.prepare(context, retry = true)
                speakMessage(
                    "Đã tải xong mô hình. Tôi đang chuẩn bị AI; đọc chữ nhanh vẫn dùng được.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_model_downloaded"
                )
            } else {
                vinternStatus = "Tải mô hình AI chưa thành công"
                speakMessage(
                    "Tải mô hình AI chưa thành công. Hãy kiểm tra mạng rồi nói tải mô hình để tải tiếp.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_model_download_failed"
                )
            }
        }
    }

    fun requestVintern(mode: VinternEngine.Mode) {
        deviceRequirements = VinternRequirements.read(context)
        val blocked = deviceRequirements.inferenceBlocker
        if (blocked != null) {
            vinternStatus = blocked
            showDeviceSetup = true
            speakMessage(blocked, TextToSpeech.QUEUE_FLUSH, null, "lumora_requirements")
            return
        }
        if (!modelReady) {
            showDeviceSetup = true
            speakMessage(
                "Chức năng này cần mô hình AI $modelSizeText. Hãy nói tải mô hình khi có Wi-Fi.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "lumora_model_missing"
            )
            return
        }
        if (!aiReady) {
            speakMessage(
                if (preparationState is VinternEngine.PreparationState.Failed)
                    "AI chưa sẵn sàng. Hãy chọn thử chuẩn bị AI lại; bạn vẫn có thể đọc chữ nhanh."
                else "AI đang chuẩn bị. Bạn có thể dùng đọc chữ nhanh trong lúc chờ.",
                TextToSpeech.QUEUE_FLUSH, null, "lumora_ai_preparing"
            )
            return
        }
        if (vinternBusy) {
            speakMessage(
                "Tôi vẫn đang phân tích, vui lòng chờ.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "lumora_vintern_busy"
            )
            return
        }
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // Tắt chế độ đọc nhanh của ML Kit để hai kết quả không đọc chồng lên nhau.
        readingMode = false
        cameraStarted = true
        vinternMode = mode
        vinternBusy = true
        vinternCancelled = false
        vinternAwaitingFrame = true
        val request = vinternRun.incrementAndGet()
        vinternSnapshot = null
        vinternStatus = if (mode == VinternEngine.Mode.DESCRIBE) {
            "AI đang quan sát và mô tả..."
        } else {
            "AI đang đọc chi tiết..."
        }
        speakMessage(
            if (mode == VinternEngine.Mode.DESCRIBE) {
                "Giữ điện thoại yên, tôi đang chụp và mô tả."
            } else {
                "Giữ điện thoại yên trên tài liệu, tôi đang chụp và đọc chi tiết. " +
                        "Việc này có thể mất vài chục giây."
            },
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lumora_vintern_start"
        )
        coroutineScope.launch {
            // Chờ người dùng giữ máy ổn định và camera kịp lấy nét rồi mới chụp.
            kotlinx.coroutines.delay(1500)
            if (vinternRun.get() != request || !vinternAwaitingFrame) return@launch
            vinternCaptureRequest = request.toInt()
            kotlinx.coroutines.delay(8000)
            if (vinternRun.get() == request && vinternAwaitingFrame && vinternCaptureRequest == request.toInt()) {
                vinternAwaitingFrame = false
                vinternBusy = false
                vinternStatus = ""
                speakMessage(
                    "Tôi chưa chụp được hình ảnh. Hãy thử lại.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_vintern_no_frame"
                )
            }
        }
    }

    fun handleVinternFrame(request: Long, frame: Bitmap) {
        if (vinternRun.get() != request || !vinternAwaitingFrame) {
            frame.recycle()
            return
        }
        vinternAwaitingFrame = false
        vinternCaptureRequest = 0
        // Bản sao chỉ dành cho UI; bitmap gốc được VinternEngine sở hữu và giải phóng.
        vinternSnapshot = frame.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
        vinternStatus = "Đang phân tích ảnh đã chụp..."
        speakMessage("Đã chụp ảnh. Tôi đang phân tích.", TextToSpeech.QUEUE_FLUSH, null, "lumora_frame_captured")
        val mode = vinternMode
        coroutineScope.launch {
            val result = VinternEngine.analyze(context, frame, mode, vinternCancellation)
            if (vinternRun.get() != request) return@launch
            vinternBusy = false
            if (vinternCancelled) {
                vinternSnapshot = null
                vinternStatus = "Đã dừng phân tích ảnh."
                return@launch
            }
            vinternStatus = ""
            when (result) {
                is VinternEngine.Result.Success -> {
                    val message = if (mode == VinternEngine.Mode.DESCRIBE) {
                        "AI mô tả: ${result.text}"
                    } else {
                        // Cho phép lệnh "đọc lại" đọc lại kết quả này.
                        lastSpokenOcrText = result.text
                        "AI đọc được: ${result.text}"
                    }
                    vinternStatus = message
                    speakMessage(
                        message,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_vintern_result"
                    )
                }
                VinternEngine.Result.ModelMissing -> {
                    vinternStatus = "Chưa có mô hình AI. Hãy mở thiết lập để tải."
                    modelReady = false
                    speakMessage(
                        "Không tìm thấy mô hình AI. Hãy nói tải mô hình.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_model_missing"
                    )
                }
                VinternEngine.Result.Failed -> {
                    vinternStatus = "Chưa phân tích được ảnh. Hãy thử lại hoặc kiểm tra thiết lập GPU."
                    speakMessage(
                        "AI chưa phân tích được hình ảnh. Hãy thử lại.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_vintern_failed"
                    )
                }
                VinternEngine.Result.Cancelled -> {
                    vinternSnapshot = null
                    vinternStatus = "Đã dừng phân tích ảnh."
                }
            }
        }
    }

    fun stopVintern() {
        vinternCancelled = true
        vinternSnapshot = null
        if (vinternBusy) vinternStatus = "Đang dừng phân tích ảnh..."
        VinternEngine.cancel(vinternCancellation)
        if (vinternAwaitingFrame) {
            vinternRun.incrementAndGet()
            vinternAwaitingFrame = false
            vinternBusy = false
            vinternStatus = ""
        }
    }

    fun startQuickReading() {
        stopVintern()
        readingMode = true
        lastSpokenOcrText = ""

        if (hasPermission) {
            cameraStarted = true
            ocrCaptureRequest++

            detectedText =
                "Đang chuẩn bị đọc. Hãy đặt toàn bộ văn bản vào giữa khung hình."

            if (ttsReady) {
                speakMessage(
                    "Đã bật chế độ đọc văn bản. " +
                            "Hãy đặt tài liệu trên mặt phẳng, " +
                            "đưa toàn bộ phần chữ vào giữa khung hình. " +
                            "Giữ điện thoại song song với mặt giấy, " +
                            "đảm bảo đủ ánh sáng và giữ điện thoại yên. " +
                            "Tôi sẽ nhận diện chữ rồi đọc cho bạn nghe. " +
                            "Nếu muốn đọc lại phần khác, hãy nói nhận diện lại.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_read_guidance"
                )
            }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    VoiceCommandListener(
        enabled = !showDeviceSetup && micPermissionGranted &&
                ((!onboardingStarted && !ttsReady) ||
                        (onboardingStarted && !onboardingSpeaking)),
        ignoreCommandsWhileSpeaking = ignoreVoiceEcho
    ) { spoken ->
        val command = spoken.lowercase(Locale.forLanguageTag("vi-VN"))
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (onboardingWaiting) {
            when {
                // Kiểm tra từ chối trước, vì câu "không cần hướng dẫn"
                // cũng có chứa cụm "hướng dẫn".
                command.contains("bỏ qua") ||
                        command.contains("không cần") ||
                        command == "không" ||
                        command.contains("bỏ") -> skipOnboarding()

                command.contains("cần hướng dẫn") ||
                        command.contains("có hướng dẫn") ||
                        command == "có" ||
                        command.contains("hướng dẫn") -> startOnboardingInstructions()

                else -> {
                    onboardingWaiting = false
                    onboardingSpeaking = true
                    val result = speakMessage(
                        "Xin lỗi, tôi chưa nghe rõ. Hãy nói cần hướng dẫn hoặc bỏ qua.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_onboarding_retry"
                    )
                    if (result != TextToSpeech.SUCCESS) {
                        onboardingSpeaking = false
                        onboardingWaiting = true
                    }
                }
            }
        } else when {
            command.contains("hướng dẫn sử dụng") ||
                    command.contains("hướng dẫn lại") ||
                    command == "hướng dẫn" -> {
                startOnboardingInstructions()
            }

            // Lệnh dùng Vintern đặt trước nhánh "đọc văn bản", vì "hãy đọc kỹ"
            // cũng chứa cụm "hãy đọc".
            command.contains("tải mô hình") -> startModelDownload()

            command.contains("mô tả") ||
                    command.contains("xung quanh có gì") ||
                    command.contains("trước mặt có gì") -> {
                requestVintern(VinternEngine.Mode.DESCRIBE)
            }

            command.contains("đọc chi tiết") ||
                    command.contains("đọc kỹ") -> {
                requestVintern(VinternEngine.Mode.READ)
            }

            command.contains("đọc văn bản") ||
                    command.contains("đọc tài liệu") ||
                    command.contains("hãy đọc") -> {

                startQuickReading()
            }

            command == "dừng đọc" ||
                    command == "ngừng đọc" ||
                    command == "dừng đọc văn bản" -> {
                stopVintern()
                ttsEngine?.stop()
                activeUtteranceId = null
                ttsSpeaking = false
                ignoreVoiceEcho = true
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!ttsSpeaking && activeUtteranceId == null) ignoreVoiceEcho = false
                }, 650L)
                speechCompletionTick++
                speakMessage(
                    "Đã dừng đọc. Bạn có thể nói đọc lại hoặc nhận diện lại.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_stop_reading"
                )
            }

            command.contains("tạm dừng quan sát") ||
                    command == "dừng quan sát" ||
                    command == "tạm dừng" -> {
                stopVintern()
                readingMode = false
                cameraStarted = false
                detectedText = "Đã tạm dừng quan sát"
                speakMessage(
                    "Đã tạm dừng quan sát.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lumora_pause"
                )
            }

            command.contains("quay lại quan sát") ||
                    command.contains("bắt đầu quan sát") ||
                    command == "quan sát" -> {
                readingMode = false
                if (hasPermission) {
                    cameraStarted = true
                    detectedText = "Đã quay lại chế độ quan sát"
                    speakMessage(
                        "Đã quay lại chế độ quan sát.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_observe"
                    )
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            command.contains("nhận diện lại") ||
                    command.contains("chụp lại") ||
                    command.contains("đọc phần khác") -> {

                if (readingMode && hasPermission) {
                    lastSpokenOcrText = ""
                    ocrCaptureRequest++
                    detectedText =
                        "Đang nhận diện lại. Hãy đặt phần văn bản mới vào giữa khung hình."

                    if (ttsReady) {
                        speakMessage(
                            "Được rồi. Hãy đưa phần văn bản cần đọc vào giữa khung hình, " +
                                    "giữ điện thoại yên. Tôi đang nhận diện lại.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "lumora_recapture_guidance"
                        )
                    }
                } else if (ttsReady) {
                    speakMessage(
                        "Bạn hãy nói đọc văn bản trước.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_recapture_not_reading"
                    )
                }
            }
            command.contains("đọc lại") -> {
                val textToRepeat = lastSpokenOcrText
                if (textToRepeat.isNotBlank() && ttsReady) {
                    speakMessage(
                        "Tôi đọc lại nội dung đã nhận diện. $textToRepeat",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_repeat_ocr"
                    )
                } else if (ttsReady) {
                    speakMessage(
                        "Chưa có văn bản nào được nhận diện để đọc lại.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_no_ocr"
                    )
                }
            }

            command.contains("lumora ơi") ||
                    command.contains("lu mora ơi") ||
                    command.contains("lu mo ra ơi") ||
                    command.contains("lumo ra ơi") ||
                    command.contains("trợ lý ơi") -> {
                if (ttsReady) {
                    speakMessage(
                        "Có tôi đây.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_wake_reply"
                    )
                }
            }
        }
    }

    if (showDeviceSetup) {
        DeviceSetupScreen(
            requirements = deviceRequirements,
            cameraGranted = hasPermission, micGranted = micPermissionGranted,
            modelReady = modelReady, downloading = modelDownloading,
            preparationState = preparationState,
            progress = modelDownloadPercent, status = vinternStatus,
            onCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onMicrophone = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onSettings = {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")))
            },
            onDownload = { startModelDownload() },
            onRefresh = {
                deviceRequirements = VinternRequirements.read(context)
                modelReady = VinternModelManager.isReady(context)
                if (modelReady) VinternEngine.prepare(context, retry = true)
            },
            onReadStatus = {
                val message = deviceRequirements.hardwareBlocker ?: deviceRequirements.inferenceBlocker
                    ?: if (modelReady) when (val state = preparationState) {
                        VinternEngine.PreparationState.Ready -> "AI đã sẵn sàng. Bạn có thể mô tả ảnh hoặc đọc chi tiết."
                        is VinternEngine.PreparationState.Failed -> state.message
                        else -> "Đã tải mô hình. AI đang chuẩn bị; đọc chữ nhanh vẫn dùng được."
                    }
                    else deviceRequirements.downloadBlocker ?: "Máy có thể tải mô hình AI, $modelSizeText."
                speakMessage(message, TextToSpeech.QUEUE_FLUSH, null, "lumora_setup_status")
            },
            onContinue = {
                onboardingPrefs.edit().putBoolean("device_setup_v1_seen", true).apply()
                showDeviceSetup = false
            }
        )
        return
    }

    val deepGreen = Color(0xFF071D17)
    val cardGreen = Color(0xFF12382B)
    val accentGreen = Color(0xFFB5F5D2)
    val softGreen = Color(0xFF8DBDA5)
    val white = Color(0xFFFFFFFF)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = deepGreen
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // HEADER LUMORA
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "LUMORA",
                        color = white,
                        fontSize = 28.sp,
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Text(
                        text = "AI VISION ASSISTANT",
                        color = accentGreen,
                        fontSize = 11.sp
                    )
                }

                Surface(
                    color = cardGreen,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (vinternBusy) {
                            "ĐANG PHÂN TÍCH ẢNH"
                        } else if (vinternSnapshot != null) {
                            "ẢNH ĐÃ CHỤP"
                        } else if (cameraStarted && readingMode) {
                            "ĐỌC CHỮ NHANH"
                        } else if (cameraStarted) {
                            "CAMERA ĐANG MỞ"
                        } else {
                            "CAMERA TẠM DỪNG"
                        },
                        color = accentGreen,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 9.dp
                        )
                    )
                }
            }

            TextButton(
                onClick = { showDeviceSetup = true },
                enabled = !vinternBusy,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("THIẾT LẬP THIẾT BỊ VÀ AI", color = accentGreen) }

            // CAMERA
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = cardGreen,
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (cameraStarted && hasPermission && vinternSnapshot == null) {
                        CameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            ocrEnabled = readingMode,
                            ocrCaptureRequest = ocrCaptureRequest,
                            onDetection = { result ->
                                detectedText = result
                            },
                            analysisPaused = vinternBusy || vinternSnapshot != null,
                            vinternCaptureRequest = vinternCaptureRequest,
                            onVinternFrame = { request, frame -> handleVinternFrame(request, frame) }
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "LUMORA",
                                color = accentGreen,
                                fontSize = 30.sp
                            )

                            Spacer(
                                modifier = Modifier.height(8.dp)
                            )

                            Text(
                                text = if (hasPermission) {
                                    "Camera sẵn sàng khởi động"
                                } else {
                                    "Cần cấp quyền camera"
                                },
                                color = white,
                                fontSize = 16.sp
                            )
                        }
                    }
                    vinternSnapshot?.let { snapshot ->
                        VinternSnapshotPreview(snapshot, vinternBusy) {
                            vinternSnapshot = null
                            vinternStatus = ""
                        }
                    }
                }
            }

            // KẾT QUẢ AI
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = cardGreen,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (vinternSnapshot != null) "KẾT QUẢ ẢNH ĐÃ CHỤP" else if (readingMode) "CHẾ ĐỘ ĐỌC TÀI LIỆU" else "KẾT QUẢ NHẬN DIỆN",
                        color = accentGreen,
                        fontSize = 12.sp
                    )

                    Text(
                        text = if (vinternSnapshot != null) vinternStatus else detectedText,
                        color = white,
                        fontSize = 16.sp
                    )

                    if (vinternStatus.isNotBlank() && vinternSnapshot == null) {
                        Text(
                            text = vinternStatus,
                            color = accentGreen,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // Hướng dẫn có cả điều khiển giọng nói và nút chạm dự phòng.
            if (onboardingStarted && !onboardingDone) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = cardGreen,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (onboardingWaiting) {
                                "BẠN CÓ CẦN HƯỚNG DẪN KHÔNG?"
                            } else {
                                "LỜI CHÀO VÀ HƯỚNG DẪN"
                            },
                            color = accentGreen,
                            fontSize = 14.sp
                        )
                        if (onboardingWaiting) {
                            Button(
                                onClick = { startOnboardingInstructions() },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentGreen,
                                    contentColor = deepGreen
                                )
                            ) {
                                Text("CẦN HƯỚNG DẪN", fontSize = 16.sp)
                            }
                        }
                        Button(
                            onClick = { skipOnboarding() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = white,
                                contentColor = deepGreen
                            )
                        ) {
                            Text("BỎ QUA HƯỚNG DẪN", fontSize = 16.sp)
                        }
                    }
                }
            }

            if (modelReady && !vinternBusy) VinternPreparationStatus(preparationState)

            // NÚT AI MÔ TẢ (Vintern); khi chưa có mô hình thì nút này dùng để tải mô hình.
            Button(
                onClick = {
                    when {
                        !modelReady -> startModelDownload()
                        preparationState is VinternEngine.PreparationState.Failed ->
                            VinternEngine.prepare(context, retry = true)
                        aiReady -> requestVintern(VinternEngine.Mode.DESCRIBE)
                    }
                },
                enabled = !vinternBusy && !modelDownloading &&
                    (!modelReady || aiReady || preparationState is VinternEngine.PreparationState.Failed),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = white,
                    contentColor = deepGreen
                )
            ) {
                Text(
                    text = when {
                        modelDownloading -> "ĐANG TẢI MÔ HÌNH AI $modelDownloadPercent%"
                        !modelReady -> "TẢI MÔ HÌNH AI (${VinternModelManager.totalBytes / (1024 * 1024)} MB)"
                        vinternBusy -> "AI ĐANG PHÂN TÍCH..."
                        preparationState is VinternEngine.PreparationState.Failed -> "THỬ CHUẨN BỊ AI LẠI"
                        !aiReady -> "ĐANG CHUẨN BỊ AI…"
                        else -> "AI MÔ TẢ XUNG QUANH"
                    },
                    fontSize = 16.sp
                )
            }

            ReadingActions(
                aiReady = modelReady && aiReady, busy = vinternBusy,
                onQuickRead = { startQuickReading() },
                onDetailedRead = { requestVintern(VinternEngine.Mode.READ) }
            )

            // NÚT ĐIỀU KHIỂN
            Button(
                onClick = {
                    if (vinternBusy) {
                        stopVintern()
                    } else if (vinternSnapshot != null) {
                        vinternSnapshot = null
                        vinternStatus = ""
                    } else if (cameraStarted) {
                        cameraStarted = false
                        readingMode = false
                        detectedText = "Đã tạm dừng quan sát"
                    } else if (hasPermission) {
                        detectedText = "Đang khởi động AI..."
                        cameraStarted = true

                    } else {
                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentGreen,
                    contentColor = deepGreen
                )
            ) {
                Text(
                    text = if (vinternBusy) {
                        "DỪNG PHÂN TÍCH ẢNH"
                    } else if (vinternSnapshot != null) {
                        "TRỞ LẠI CAMERA"
                    } else if (cameraStarted) {
                        "TẠM DỪNG QUAN SÁT"
                    } else {
                        "BẮT ĐẦU QUAN SÁT"
                    },
                    fontSize = 16.sp
                )
            }

            Text(
                text = "LUMORA hỗ trợ quan sát, không thay thế " +
                        "việc tự đánh giá an toàn khi di chuyển.",
                color = softGreen,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun VoiceCommandListener(
    enabled: Boolean,
    ignoreCommandsWhileSpeaking: Boolean = false,
    onCommand: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCommand by rememberUpdatedState(onCommand)
    val latestIgnoreCommandsWhileSpeaking by rememberUpdatedState(ignoreCommandsWhileSpeaking)
    var activityResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> activityResumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> activityResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Chỉ Activity ở trạng thái RESUMED mới nghe lệnh đầy đủ. Khi Activity
    // chuyển ra nền, recognizer này bị hủy trước khi Service bắt đầu nghe wake word.
    DisposableEffect(context, enabled, activityResumed) {
        if (!enabled || !activityResumed || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onDispose { }
        } else {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val handler = Handler(Looper.getMainLooper())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            // Ghi nhớ trạng thái tại lúc bắt đầu một phiên nghe. Nếu phiên đó bắt
            // đầu khi TTS đang nói, bỏ kết quả phiên đó để tránh nhận nhầm tiếng vọng
            // ngay cả khi TTS đã kết thúc trước lúc callback onResults đến.
            var sessionStartedDuringSpeech = false
            val restartListening = object : Runnable {
                override fun run() {
                    try {
                        sessionStartedDuringSpeech = latestIgnoreCommandsWhileSpeaking
                        recognizer.startListening(intent)
                    } catch (error: Exception) {
                        Log.w("LUMORA_VOICE", "Không thể bắt đầu nghe lệnh", error)
                        handler.postDelayed(this, 1200L)
                    }
                }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val spoken = matches?.firstOrNull().orEmpty()
                    val normalizedSpoken = spoken.lowercase(Locale.forLanguageTag("vi-VN"))
                        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    // Khi đang phát TTS, bỏ qua nội dung micro thu lại từ ứng dụng.
                    // Chỉ giữ một số lệnh dừng ngắn để người dùng vẫn có thể ngắt lời đọc.
                    val emergencyStop = normalizedSpoken in setOf(
                        "tạm dừng", "dừng lại", "tạm dừng quan sát", "dừng quan sát",
                        "dừng đọc", "ngừng đọc", "dừng đọc văn bản"
                    )
                    val sessionMayContainTtsEcho =
                        sessionStartedDuringSpeech || latestIgnoreCommandsWhileSpeaking
                    if (spoken.isNotBlank() &&
                        (!sessionMayContainTtsEcho || emergencyStop)
                    ) {
                        latestOnCommand(spoken)
                    }
                    handler.removeCallbacks(restartListening)
                    handler.postDelayed(restartListening, 900L)
                }

                override fun onError(error: Int) {
                    handler.removeCallbacks(restartListening)
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        handler.postDelayed(
                            restartListening,
                            if (error == SpeechRecognizer.ERROR_CLIENT) 1200L else 900L
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
            })

            handler.post(restartListening)

            onDispose {
                handler.removeCallbacks(restartListening)
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    ocrEnabled: Boolean,
    ocrCaptureRequest: Int,
    onDetection: (String) -> Unit,
    analysisPaused: Boolean = false,
    vinternCaptureRequest: Int = 0,
    onVinternFrame: (Long, Bitmap) -> Unit = { _, frame -> frame.recycle() }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) {
        ContextCompat.getMainExecutor(context)
    }
    val cameraProviderRef = remember {
        arrayOfNulls<ProcessCameraProvider>(1)
    }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val latestOnDetection by rememberUpdatedState(onDetection)
    val publishDetection: (String) -> Unit = remember {
        { result -> if (!disposed.get()) latestOnDetection(result) }
    }
    val lastAnalysisAt = remember { AtomicLong(0L) }
    val lastOcrAt = remember { AtomicLong(0L) }
    val currentOcrEnabled by rememberUpdatedState(ocrEnabled)
    // Mỗi lần vào/thoát chế độ đọc hoặc yêu cầu chụp lại, tăng thế hệ OCR.
    // Kết quả bất đồng bộ của khung hình cũ sẽ bị bỏ qua.
    val ocrRequestGeneration = remember { AtomicLong(0L) }

    // Khi số yêu cầu Vintern tăng, analyzer gửi một bản sao khung hình kế tiếp.
    // Trong lúc Vintern phân tích, tạm dừng EfficientDet/ML Kit để nhường CPU.
    val currentAnalysisPaused by rememberUpdatedState(analysisPaused)
    val latestOnVinternFrame by rememberUpdatedState(onVinternFrame)
    val vinternRequested = remember { AtomicLong(0L) }
    val vinternHandled = remember { AtomicLong(0L) }
    LaunchedEffect(vinternCaptureRequest) {
        vinternRequested.set(vinternCaptureRequest.toLong())
    }

    val ocrInProgress = remember {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }

// Lưu kết quả OCR đang thử xác nhận.
    val ocrCandidateText = remember {
        java.util.concurrent.atomic.AtomicReference("")
    }

    val ocrCandidateCount = remember {
        AtomicLong(0L)
    }

// Khi đã chốt được văn bản thì dừng OCR tự động.
    val ocrCaptured = remember {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }

// Khai báo các biến trước khi sử dụng chúng.
    val latestObjectText = remember {
        java.util.concurrent.atomic.AtomicReference(
            "Chưa phát hiện vật thể rõ ràng"
        )
    }

    val latestOcrText = remember {
        java.util.concurrent.atomic.AtomicReference("")
    }

// Đặt LaunchedEffect sau phần khai báo latestOcrText.
    LaunchedEffect(ocrEnabled, ocrCaptureRequest) {
        val requestGeneration = ocrRequestGeneration.incrementAndGet()
        if (ocrEnabled) {
            latestOcrText.set("")
            ocrCandidateText.set("")
            ocrCandidateCount.set(0L)
            ocrCaptured.set(false)

            // Nếu không tìm thấy chữ, báo bằng giọng nói thay vì chờ vô hạn.
            kotlinx.coroutines.delay(6500L)
            if (
                currentOcrEnabled &&
                requestGeneration == ocrRequestGeneration.get() &&
                ocrCaptured.compareAndSet(false, true)
            ) {
                publishDetection(
                    "OCR_STATUS:Tôi chưa đọc được chữ rõ ràng. " +
                            "Hãy đưa phần chữ vào giữa khung hình, tăng ánh sáng rồi nói nhận diện lại."
                )
            }
        } else {
            ocrCaptured.set(false)
            latestOcrText.set("")
        }
    }


    val textRecognizer = remember(context) {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }

    // Tải model một lần khi CameraPreview được tạo.
    val objectDetector = remember(context) {
        try {
            ObjectDetector.createFromFile(
                context,
                "efficientdet-lite0.tflite"
            )
        } catch (error: Exception) {
            Log.e(
                "LUMORA_TFLITE",
                "Không tải được efficientdet-lite0.tflite",
                error
            )
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            ocrRequestGeneration.incrementAndGet()
            cameraProviderRef[0]?.unbindAll()
            cameraProviderRef[0] = null
            // Đợi các tác vụ analyzer đã xếp hàng xong trước khi đóng detector.
            executor.execute {
                try { textRecognizer.close() } finally { objectDetector?.close() }
            }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

            val providerFuture =
                ProcessCameraProvider.getInstance(viewContext)

            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    if (disposed.get()) return@addListener
                    cameraProviderRef[0] = provider

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }


                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(1280, 720))
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .setOutputImageFormat(
                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .build()

                    analysis.setAnalyzer(executor) { imageProxy ->
                        try {
                            if (disposed.get()) return@setAnalyzer
                            if (currentAnalysisPaused && vinternRequested.get() <= vinternHandled.get()) {
                                return@setAnalyzer
                            }
                            val now = System.currentTimeMillis()

                            // Giới hạn tần suất xử lý để giảm tải điện thoại.
                            if (now - lastAnalysisAt.get() < 450L) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            lastAnalysisAt.set(now)

                            if (objectDetector == null) {
                                mainExecutor.execute {
                                    publishDetection("Không tải được model AI")
                                }
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val bitmap = imageProxyToBitmap(imageProxy)
                            val rotation =
                                imageProxy.imageInfo.rotationDegrees

                            val rotatedBitmap = if (rotation == 0) {
                                bitmap
                            } else {
                                val matrix = Matrix().apply {
                                    postRotate(rotation.toFloat())
                                }

                                Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.width,
                                    bitmap.height,
                                    matrix,
                                    true
                                ).also {
                                    if (it !== bitmap) bitmap.recycle()
                                }
                            }

                            try {
                                val requestedFrame = vinternRequested.get()
                                if (requestedFrame > vinternHandled.get()) {
                                    vinternHandled.set(requestedFrame)
                                    val frame = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    mainExecutor.execute {
                                        if (disposed.get()) frame.recycle()
                                        else latestOnVinternFrame(requestedFrame, frame)
                                    }
                                }
                                if (currentAnalysisPaused) return@setAnalyzer

                                // PHẦN 1: Nhận diện vật thể EfficientDet.
                                val image = TensorImage.fromBitmap(
                                    rotatedBitmap
                                )
                                val detections = objectDetector.detect(image)

                                // Lấy cả nhãn, độ tin cậy và vị trí khung bao.
                                // Vị trí/kích thước chỉ là ước lượng tương đối trong ảnh,
                                // không phải khoảng cách hay kích thước thật ngoài đời.
                                val result = detections
                                    .flatMap { detection ->
                                        val box = detection.boundingBox
                                        val centerX = box.centerX() / rotatedBitmap.width.toFloat()
                                        val centerY = box.centerY() / rotatedBitmap.height.toFloat()
                                        val areaRatio =
                                            (box.width() * box.height()) /
                                                    (rotatedBitmap.width.toFloat() * rotatedBitmap.height.toFloat())

                                        val horizontalPosition = when {
                                            centerX < 0.15f -> "sát mép trái"
                                            centerX < 0.35f -> "hơi lệch trái"
                                            centerX > 0.85f -> "sát mép phải"
                                            centerX > 0.65f -> "hơi lệch phải"
                                            else -> "gần giữa khung hình"
                                        }
                                        val verticalPosition = when {
                                            centerY < 0.33f -> "vùng trên khung hình"
                                            centerY > 0.67f -> "vùng dưới khung hình"
                                            else -> ""
                                        }
                                        val position = buildString {
                                            append("trong tầm nhìn phía trước, ")
                                            append(horizontalPosition)
                                            if (verticalPosition.isNotBlank()) {
                                                append(", ")
                                                append(verticalPosition)
                                            }
                                        }
                                        val relativeSize = when {
                                            areaRatio >= 0.22f -> "kích thước lớn trong ảnh"
                                            areaRatio >= 0.07f -> "kích thước vừa trong ảnh"
                                            else -> "kích thước nhỏ trong ảnh"
                                        }

                                        detection.categories.mapNotNull { category ->
                                            val label = category.label
                                            val score = category.score

                                            if (label.isBlank() || score < 0.35f) {
                                                null
                                            } else {
                                                "$label (${(score * 100).toInt()}%) [$position | $relativeSize]"
                                            }
                                        }
                                    }
                                    .distinct()
                                    .take(4)
                                    .joinToString("; ")
                                    .ifBlank {
                                        "Chưa phát hiện vật thể rõ ràng"
                                    }

                                latestObjectText.set(result)

                                // Hiển thị vật thể và chữ đã nhận diện.
                                val previousOcr = latestOcrText.get()

                                val currentDisplay =
                                    if (previousOcr.isNotBlank()) {
                                        "$result\nVăn bản: $previousOcr"
                                    } else {
                                        result
                                    }

                                // Khi đọc tài liệu, không cập nhật kết quả mỗi khung hình.
// Tránh làm gián đoạn bộ đếm thời gian đọc giọng nói.
// EfficientDet vẫn chạy bình thường.
                                if (!currentOcrEnabled) {
                                    mainExecutor.execute {
                                        publishDetection(currentDisplay)
                                    }
                                }

                                // PHẦN 2: OCR khoảng mỗi 1,8 giây.
                                val ocrNow = System.currentTimeMillis()

                                if (
                                    currentOcrEnabled &&
                                    !ocrCaptured.get() &&
                                    ocrNow - lastOcrAt.get() >= 800L &&
                                    ocrInProgress.compareAndSet(
                                        false,
                                        true
                                    )
                                ) {
                                    lastOcrAt.set(ocrNow)
                                    val ocrGenerationAtStart = ocrRequestGeneration.get()

                                    val ocrBitmap = createOcrContrastBitmap(rotatedBitmap)

                                    if (ocrBitmap == null) {
                                        ocrInProgress.set(false)
                                    } else {
                                        try {
                                            textRecognizer.process(
                                                InputImage.fromBitmap(
                                                    ocrBitmap,
                                                    0
                                                )
                                            )


                                                .addOnSuccessListener(mainExecutor) { visionText ->
                                                    if (disposed.get()) return@addOnSuccessListener
                                                    val recognizedText = visionText.text
                                                        .trim()
                                                        .take(1000)

                                                    Log.d(
                                                        "LUMORA_OCR",
                                                        "Ảnh OCR: ${ocrBitmap.width}x${ocrBitmap.height}"
                                                    )
                                                    Log.d(
                                                        "LUMORA_OCR",
                                                        "OCR: ${recognizedText.length} ký tự"
                                                    )

                                                    if (
                                                        recognizedText.isNotBlank() &&
                                                        currentOcrEnabled &&
                                                        ocrGenerationAtStart == ocrRequestGeneration.get()
                                                    ) {
                                                        // Đọc ngay khi có kết quả chữ đầu tiên để tránh chờ
                                                        // nhiều vòng xác nhận. Có thể yêu cầu "nhận diện lại" để thử lại.
                                                        if (
                                                            currentOcrEnabled &&
                                                            ocrGenerationAtStart == ocrRequestGeneration.get() &&
                                                            ocrCaptured.compareAndSet(false, true)
                                                        ) {
                                                            latestOcrText.set(recognizedText)
                                                            val latestObject = latestObjectText.get()
                                                            val combinedResult =
                                                                "$latestObject\nVăn bản: $recognizedText"
                                                            publishDetection(combinedResult)
                                                            Log.d("LUMORA_OCR", "Đã nhận chữ và chuyển sang đọc")
                                                        }
                                                    } else {
                                                        Log.d(
                                                            "LUMORA_OCR",
                                                            "Chưa thấy chữ hoặc chế độ đọc đã tắt"
                                                        )
                                                    }
                                                }

                                                .addOnFailureListener(
                                                    mainExecutor
                                                ) { error ->
                                                    Log.w(
                                                        "LUMORA_OCR",
                                                        "Không nhận diện được chữ",
                                                        error
                                                    )
                                                }
                                                .addOnCompleteListener(
                                                    mainExecutor
                                                ) {
                                                    if (!ocrBitmap.isRecycled) {
                                                        ocrBitmap.recycle()
                                                    }
                                                    ocrInProgress.set(false)
                                                }
                                        } catch (ocrError: Exception) {
                                            if (!ocrBitmap.isRecycled) {
                                                ocrBitmap.recycle()
                                            }

                                            ocrInProgress.set(false)

                                            Log.e(
                                                "LUMORA_OCR",
                                                "Lỗi khi khởi chạy OCR",
                                                ocrError
                                            )
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                Log.e(
                                    "LUMORA_TFLITE",
                                    "Lỗi phân tích ảnh bằng EfficientDet",
                                    error
                                )

                                mainExecutor.execute {
                                    publishDetection(
                                        "AI gặp lỗi khi phân tích ảnh"
                                    )
                                }
                            } finally {
                                if (!rotatedBitmap.isRecycled) {
                                    rotatedBitmap.recycle()
                                }
                            }
                        } catch (error: Exception) {
                            Log.e(
                                "LUMORA_TFLITE",
                                "Không chuyển được khung hình camera",
                                error
                            )
                            mainExecutor.execute {
                                publishDetection(
                                    "Không xử lý được hình ảnh camera"
                                )
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (error: Exception) {
                    Log.e(
                        "LUMORA_TFLITE",
                        "Không thể khởi động camera hoặc AI",
                        error
                    )
                    mainExecutor.execute {
                        publishDetection("Không thể khởi động camera/AI")
                    }
                }
            }, mainExecutor)

            previewView
        }
    )
}

/**
 * Chuyển CameraX RGBA_8888 ImageProxy thành Bitmap,
 * có xử lý rowStride/pixelStride.
 */
private fun imageProxyToBitmap(
    imageProxy: ImageProxy
): Bitmap {
    val plane = imageProxy.planes[0]
    val buffer = plane.buffer.duplicate()
    val width = imageProxy.width
    val height = imageProxy.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val pixels = IntArray(width * height)

    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * rowStride + x * pixelStride

            if (index + 3 < bytes.size) {
                val r = bytes[index].toInt() and 0xFF
                val g = bytes[index + 1].toInt() and 0xFF
                val b = bytes[index + 2].toInt() and 0xFF
                val a = bytes[index + 3].toInt() and 0xFF

                pixels[y * width + x] =
                    (a shl 24) or
                            (r shl 16) or
                            (g shl 8) or b
            }
        }
    }

    return Bitmap.createBitmap(
        pixels,
        width,
        height,
        Bitmap.Config.ARGB_8888
    )
}

fun translateDetectionForSpeech(raw: String): String? {
    if (raw.startsWith("OCR_STATUS:")) {
        return raw
    }
    val normalized = raw.trim().lowercase()

    // Ưu tiên đọc chữ OCR nếu camera nhận diện được.
    val ocrMatch = Regex(
        "(?s)Văn bản:\\s*(.*)$"
    ).find(raw)

    val ocrText = ocrMatch?.groupValues?.get(1)?.trim()

    if (!ocrText.isNullOrBlank()) {
        return "Nội dung nhận diện được như sau: $ocrText"
    }

    val ignoredMessages = listOf(
        "chưa phát hiện vật thể",
        "chưa phát hiện vật thể rõ ràng",
        "vật thể chưa phân loại",
        "ai gặp lỗi",
        "không thể khởi động camera/ai",
        "đã tạm dừng quan sát",
        "đang chờ phân tích hình ảnh",
        "đang khởi động ai",
        "đang khởi động nhận diện ai",
        "bạn cần cấp quyền camera",
        "không tải được model ai",
        "không xử lý được hình ảnh camera"
    )

    if (ignoredMessages.any { normalized.startsWith(it) }) {
        return null
    }

    val vietnameseLabels = mapOf(
        "person" to "người",
        "bicycle" to "xe đạp",
        "car" to "ô tô",
        "motorcycle" to "xe máy",
        "bus" to "xe buýt",
        "train" to "tàu hỏa",
        "truck" to "xe tải",
        "traffic light" to "đèn giao thông",
        "stop sign" to "biển dừng",
        "bench" to "ghế băng",
        "cat" to "mèo",
        "dog" to "chó",
        "backpack" to "ba lô",
        "umbrella" to "ô",
        "handbag" to "túi xách",
        "suitcase" to "va li",
        "sports ball" to "bóng",
        "skateboard" to "ván trượt",
        "bottle" to "chai",
        "wine glass" to "ly chân cao",
        "cup" to "cốc",
        "fork" to "nĩa",
        "knife" to "dao",
        "spoon" to "thìa",
        "bowl" to "bát",
        "banana" to "chuối",
        "apple" to "táo",
        "sandwich" to "bánh mì kẹp",
        "orange" to "cam",
        "broccoli" to "bông cải xanh",
        "carrot" to "cà rốt",
        "hot dog" to "bánh mì xúc xích",
        "pizza" to "pizza",
        "donut" to "bánh vòng",
        "cake" to "bánh ngọt",
        "chair" to "ghế",
        "couch" to "ghế sofa",
        "potted plant" to "chậu cây",
        "bed" to "giường",
        "dining table" to "bàn",
        "toilet" to "bồn cầu",
        "tv" to "ti vi",
        "laptop" to "máy tính xách tay",
        "mouse" to "chuột máy tính",
        "remote" to "điều khiển từ xa",
        "keyboard" to "bàn phím",
        "cell phone" to "điện thoại",
        "microwave" to "lò vi sóng",
        "oven" to "lò nướng",
        "toaster" to "máy nướng bánh",
        "sink" to "bồn rửa",
        "refrigerator" to "tủ lạnh",
        "book" to "sách",
        "clock" to "đồng hồ",
        "vase" to "bình hoa",
        "scissors" to "kéo",
        "teddy bear" to "gấu bông",
        "toothbrush" to "bàn chải đánh răng",
        // Nhãn bổ sung / cách gọi gần nghĩa; chỉ dịch nhãn model trả về, không thêm lớp cho EfficientDet.
        "airplane" to "máy bay",
        "aeroplane" to "máy bay",
        "motorbike" to "xe máy",
        "bike" to "xe đạp",
        "sofa" to "ghế sofa",
        "mobile phone" to "điện thoại",
        "cellphone" to "điện thoại",
        "smartphone" to "điện thoại thông minh",
        "purse" to "ví hoặc túi nhỏ",
        "wallet" to "ví",
        "door" to "cửa",
        "window" to "cửa sổ",
        "stairs" to "cầu thang",
        "staircase" to "cầu thang",
        "table" to "bàn",
        "desk" to "bàn làm việc",
        "monitor" to "màn hình máy tính",
        "computer" to "máy tính",
        "printer" to "máy in",
        "fan" to "quạt",
        "lamp" to "đèn",
        "light" to "đèn",
        "plant" to "cây",
        "tree" to "cây",
        "flower" to "hoa",
        "cupboard" to "tủ",
        "cabinet" to "tủ",
        "box" to "hộp",
        "package" to "gói hàng",
        "helmet" to "mũ bảo hiểm",
        "shoe" to "giày",
        "shoes" to "giày",
        "slipper" to "dép",
        "slippers" to "dép",
        "hat" to "mũ",
        "clothing" to "quần áo",
        "shirt" to "áo",
        "pants" to "quần",
        "person sitting" to "người đang ngồi",
        "human" to "người",
        "road" to "đường",
        "street" to "đường",
        "pavement" to "vỉa hè",
        "sidewalk" to "vỉa hè",
        "fence" to "hàng rào",
        "pole" to "cột",
        "post" to "cột",
        "sign" to "biển báo",
        "traffic sign" to "biển báo giao thông",
        "stairs railing" to "tay vịn cầu thang",
        "water" to "nước",
        "food" to "thức ăn",
        "plate" to "đĩa",
        "pan" to "chảo",
        "pot" to "nồi",
        "key" to "chìa khóa",
        "keys" to "chùm chìa khóa",
        "remote control" to "điều khiển từ xa",
        "air conditioner" to "máy điều hòa",
        "aircon" to "máy điều hòa",
        "washing machine" to "máy giặt",
        "fan heater" to "quạt sưởi",
        "unknown" to "vật thể không xác định"
    )

    // Mỗi vật thể được ngăn cách bằng dấu chấm phẩy; thông tin vị trí nằm trong [...].
    val labels = raw.split(";")
        .map { item ->
            val match = Regex(
                "^(.+?)\\s*\\((\\d+)%\\)(?:\\s*\\[(.*)])?$"
            ).find(item.trim())

            val label = match?.groupValues?.get(1)?.trim()
                ?: item.trim()
            val score = match?.groupValues?.get(2)
            val details = match?.groupValues?.get(3).orEmpty()
            val detailParts = details.split("|").map { it.trim() }.filter { it.isNotBlank() }
            val position = detailParts.getOrNull(0)
            val relativeSize = detailParts.getOrNull(1)

            // Nếu model trả về nhãn ngoài từ điển, vẫn thông báo có vật thể thay vì im lặng.
            val translated = OnlineVietnameseDictionary.translate(label)
                ?: vietnameseLabels[label.lowercase(Locale.ROOT)]
                ?: "vật thể chưa xác định"

            buildString {
                if (!position.isNullOrBlank()) {
                    append(position.replaceFirstChar { it.uppercase() })
                    append(": ")
                }
                append(translated)
            }
        }
        .distinct()
        .take(2)

    if (labels.isEmpty()) return null

    return labels.joinToString(". ")
}

