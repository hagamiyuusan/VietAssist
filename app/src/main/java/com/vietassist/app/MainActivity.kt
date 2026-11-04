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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicLong
import com.vietassist.app.ui.theme.VietAssistTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun syncVoiceService() {
        if (automaticListeningAllowed(VoiceSettings.get(this).alwaysListening.value,
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ) {
            startLumoraVoiceService(LumoraVoiceService.ACTION_INIT, foregroundStart = true)
        } else {
            stopService(Intent(this, LumoraVoiceService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        syncVoiceService()
    }

    override fun onStop() {
        // VoiceCommandListener sẽ tự dừng ở ON_PAUSE. Chỉ sau khi Activity rời
        // màn hình, dịch vụ mới được phép nghe cụm đánh thức trong nền.
        if (automaticListeningAllowed(VoiceSettings.get(this).alwaysListening.value,
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
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
    val ocrSettings = remember(context) { OcrSettings.get(context) }
    val ocrBackend by ocrSettings.backend.collectAsState()
    var capturedOcrBackend by remember { mutableStateOf(OcrBackend.ML_KIT) }
    val voiceSettings = remember(context) { VoiceSettings.get(context) }
    val alwaysListening by voiceSettings.alwaysListening.collectAsState()
    var manualVoicePending by rememberSaveable { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("") }
    var voiceNoticeId by remember { mutableIntStateOf(0) }
    var voiceListening by remember { mutableStateOf(false) }
    fun showVoiceNotice(message: String) { voiceStatus = message; voiceNoticeId++ }
    LaunchedEffect(voiceNoticeId) {
        val request = voiceNoticeId
        if (request > 0) {
            kotlinx.coroutines.delay(6000)
            if (voiceNoticeId == request) voiceStatus = ""
        }
    }
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

    var appSpeech by remember { mutableStateOf("") }
    var ttsStatus by remember { mutableStateOf("Đang chuẩn bị giọng Việt…") }
    var ttsReady by remember {
        mutableStateOf(false)
    }

    var detectionEnabled by remember {
        mutableStateOf(true)
    }

    var onboardingStarted by remember { mutableStateOf(onboardingAlreadyCompleted) }
    var onboardingWaiting by remember { mutableStateOf(false) }
    var onboardingSpeaking by remember { mutableStateOf(false) }
    var onboardingDone by remember { mutableStateOf(onboardingAlreadyCompleted) }
    var micPermissionRequestPending by remember { mutableStateOf(false) }
    // Vintern (AI đọc ảnh chạy trên máy): chụp một khung hình rồi mô tả hoặc đọc chi tiết.
    val coroutineScope = rememberCoroutineScope()
    var vinternCaptureRequest by remember { mutableIntStateOf(0) }
    val vinternRun = remember { AtomicLong(0) }
    val vinternCancellation = remember { AtomicLong(0) }
    var hasCapturedResult by remember { mutableStateOf(false) }
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

    // Nội dung đọc bởi model đã cấu hình, dùng lại cho lệnh đọc lại.
    var lastReadText by remember {
        mutableStateOf("")
    }

    // Trạng thái TTS dùng chung. Mỗi lượt đọc có ID riêng để callback cũ
    // không thể mở khóa nhầm khi một câu mới đã bắt đầu.
    var ttsSpeaking by remember { mutableStateOf(false) }
    var activeUtteranceId by remember { mutableStateOf<String?>(null) }
    var ignoreVoiceEcho by remember { mutableStateOf(false) }
    var speechCompletionTick by remember { mutableStateOf(0) }

    var ttsReload by remember { mutableIntStateOf(0) }
    val speechLifecycle = LocalLifecycleOwner.current
    DisposableEffect(speechLifecycle) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused) { paused = false; ttsReload++ }
        }
        speechLifecycle.lifecycle.addObserver(observer)
        onDispose { speechLifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context, ttsReload) {
        var engine: TextToSpeech? = null
        var disposed = false
        engine = TextToSpeech(context) { status ->
            if (disposed) return@TextToSpeech
            val currentEngine = engine

            if (status == TextToSpeech.SUCCESS && currentEngine != null) {
                ttsReady = VietnameseSpeech.configure(currentEngine)
                val label = currentEngine.engines.firstOrNull { it.name == currentEngine.defaultEngine }?.label ?: "Bộ đọc của máy"
                ttsStatus = if (ttsReady) "Giọng Việt đã sẵn sàng · $label"
                    else "Chưa có giọng Việt sẵn sàng. Mở cài đặt giọng đọc để tải hoặc chọn bộ đọc hỗ trợ tiếng Việt."
                Log.i("LUMORA_TTS", "engine=${currentEngine.defaultEngine}, locale=${currentEngine.voice?.locale?.toLanguageTag()}, ready=$ttsReady")
            } else {
                ttsReady = false
                ttsStatus = "Không khởi tạo được giọng đọc. Hãy kiểm tra cài đặt giọng nói."
                Log.e(
                    "LUMORA_TTS",
                    "Khởi tạo TextToSpeech thất bại: $status"
                )
            }
        }

        ttsEngine = engine

        onDispose {
            disposed = true
            activeUtteranceId = null
            appSpeech = ""
            ttsSpeaking = false
            ignoreVoiceEcho = false
            engine?.stop()
            engine?.shutdown()
            ttsEngine = null
            ttsReady = false
        }
    }

    fun speakMessage(
        text: String,
        queueMode: Int,
        params: Bundle?,
        utteranceId: String
    ): Int {
        val engine = ttsEngine ?: return TextToSpeech.ERROR
        if (!ttsReady) return TextToSpeech.ERROR
        // ID duy nhất, kể cả khi cùng một lệnh được phát nhiều lần.
        val uniqueUtteranceId = "${utteranceId}__run_${System.nanoTime()}"
        activeUtteranceId = uniqueUtteranceId
        appSpeech = text
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
                    "Nói đọc văn bản để dùng OCR đã chọn trong Thiết lập. " +
                    "Nói chụp lại để phân tích ảnh mới. " +
                    "Nói chụp và mô tả chi tiết để AI phân tích một ảnh trước mặt, " +
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
                        if (failed) showVoiceNotice("Không phát được giọng đọc. Mở Thiết lập để kiểm tra giọng Việt.")
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
                        (if (alwaysListening) "Để luôn nghe lệnh, ứng dụng cần quyền sử dụng micro. Sau lời chào, điện thoại sẽ hỏi quyền micro."
                        else "Bạn có thể nhấn nút Nhấn để nói để ra lệnh, hoặc dùng các nút chức năng trên màn hình."),
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
        onboardingSpeaking,
        onboardingWaiting,
        speechCompletionTick,
        vinternBusy,
        hasCapturedResult,
        showDeviceSetup
    ) {
        val message = speechText ?: return@LaunchedEffect
        if (showDeviceSetup || !ttsReady || message.isBlank()) return@LaunchedEffect
        // Trong lúc AI phân tích ảnh, không chen thông báo vật thể vào.
        if (vinternBusy || hasCapturedResult) return@LaunchedEffect

        // Debounce các cập nhật khung hình liên tiếp; chỉ lấy kết quả mới nhất.
        kotlinx.coroutines.delay(220)

        if (onboardingSpeaking || onboardingWaiting || message == lastSpokenText) return@LaunchedEffect

        // Không bao giờ dùng QUEUE_FLUSH để cắt ngang một câu tự động đang phát.
        // Một số TTS báo isSpeaking chậm hơn callback, nên chờ ngắn rồi kiểm tra lại.
        if (ttsSpeaking || ttsEngine?.isSpeaking == true) {
            kotlinx.coroutines.delay(180)
            if (ttsSpeaking || ttsEngine?.isSpeaking == true) return@LaunchedEffect
        }

        val remaining = 1800L - (System.currentTimeMillis() - lastSpokenAt)
        if (remaining > 0L) kotlinx.coroutines.delay(remaining)

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
            detectionEnabled = granted

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

    fun startManualVoice() {
        ttsEngine?.stop()
        voiceStatus = ""
        manualVoicePending = true
    }

    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            micPermissionGranted = granted
            if (!granted) manualVoicePending = false
            if (granted) {
                hostActivity?.syncVoiceService()
                if (manualVoicePending && !alwaysListening) {
                    startManualVoice()
                } else if (!showDeviceSetup && Build.VERSION.SDK_INT >= 33 &&
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

    LaunchedEffect(micPermissionRequestPending, micPermissionGranted, alwaysListening) {
        if (micPermissionRequestPending) {
            micPermissionRequestPending = false
            if (!alwaysListening || micPermissionGranted) {
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
                    else -> "Mô hình đã tải xong. AI đang chuẩn bị; bạn vẫn có thể dùng OCR ML Kit trong Thiết lập."
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
                    "Đã tải xong mô hình. Tôi đang chuẩn bị AI; OCR bằng ML Kit vẫn dùng được.",
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

    fun requestCapture(mode: VinternEngine.Mode) {
        val useVintern = usesVintern(mode, ocrSettings.backend.value)
        if (useVintern) {
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
                        "AI chưa sẵn sàng. Hãy chọn thử chuẩn bị AI lại; bạn vẫn có thể dùng OCR ML Kit trong Thiết lập."
                    else "AI đang chuẩn bị. Bạn vẫn có thể đọc bằng OCR ML Kit nếu đã chọn trong Thiết lập.",
                    TextToSpeech.QUEUE_FLUSH, null, "lumora_ai_preparing"
                )
                return
            }
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

        capturedOcrBackend = ocrSettings.backend.value
        detectionEnabled = true
        vinternMode = mode
        vinternBusy = true
        vinternCancelled = false
        vinternAwaitingFrame = true
        val request = vinternRun.incrementAndGet()
        hasCapturedResult = false
        vinternStatus = if (mode == VinternEngine.Mode.DESCRIBE) {
            "Đang chụp ảnh để mô tả chi tiết..."
        } else {
            "Đang chụp để đọc chữ bằng ${capturedOcrBackend.label}..."
        }
        speakMessage(
            if (mode == VinternEngine.Mode.DESCRIBE) {
                "Giữ điện thoại yên, tôi đang chụp và mô tả."
            } else {
                "Giữ điện thoại yên trên tài liệu, tôi đang chụp để đọc chữ bằng ${capturedOcrBackend.label}."
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

    fun handleCapturedFrame(request: Long, frame: Bitmap) {
        if (vinternRun.get() != request || !vinternAwaitingFrame) {
            frame.recycle()
            return
        }
        vinternAwaitingFrame = false
        vinternCaptureRequest = 0
        // Bản sao chỉ dành cho UI; bitmap gốc được VinternEngine sở hữu và giải phóng.
        hasCapturedResult = true
        vinternStatus = "Đang phân tích ảnh đã chụp..."
        speakMessage("Đã chụp ảnh. Tôi đang phân tích.", TextToSpeech.QUEUE_FLUSH, null, "lumora_frame_captured")
        val mode = vinternMode
        val backend = capturedOcrBackend
        coroutineScope.launch {
            val result = if (!usesVintern(mode, backend))
                MlKitOcr.read(frame)
            else VinternEngine.analyze(context, frame, mode, vinternCancellation)
            if (vinternRun.get() != request) return@launch
            vinternBusy = false
            if (vinternCancelled) {
                hasCapturedResult = false
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
                        lastReadText = result.text
                        "${backend.label} đọc được: ${result.text}"
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
                    vinternStatus = if (usesVintern(mode, backend)) "AI chưa phân tích được ảnh. Hãy thử lại hoặc kiểm tra Thiết lập."
                        else "OCR ML Kit chưa đọc được ảnh. Hãy giữ máy yên, đủ sáng rồi nói chụp lại."
                    speakMessage(
                        vinternStatus,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_vintern_failed"
                    )
                }
                VinternEngine.Result.Cancelled -> {
                    hasCapturedResult = false
                    vinternStatus = "Đã dừng phân tích ảnh."
                }
            }
        }
    }

    fun stopVintern() {
        vinternCancelled = true
        hasCapturedResult = false
        if (vinternBusy) vinternStatus = "Đang dừng phân tích ảnh..."
        VinternEngine.cancel(vinternCancellation)
        if (vinternAwaitingFrame) {
            vinternRun.incrementAndGet()
            vinternAwaitingFrame = false
            vinternBusy = false
            vinternStatus = ""
        }
    }

    fun handleVoiceCommand(spoken: String) {
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
        } else {
            val action = parseVoiceCommand(spoken)
            Log.i("LUMORA_VOICE", "command=${action?.name ?: "UNKNOWN"}, ocr=${ocrSettings.backend.value.name}")
            showVoiceNotice(if (action == null) "Chưa nhận ra lệnh. Thử nói “đọc văn bản” hoặc “mô tả xung quanh”."
                else "Đã nhận lệnh: ${action.label}" + if (action == VoiceCommand.READ) " · ${ocrSettings.backend.value.label}" else "")
            when (action) {
                VoiceCommand.HELP -> {
                    startOnboardingInstructions()
                }

                VoiceCommand.DOWNLOAD -> startModelDownload()

                VoiceCommand.DESCRIBE -> {
                    requestCapture(VinternEngine.Mode.DESCRIBE)
                }

                VoiceCommand.READ -> { requestCapture(VinternEngine.Mode.READ) }

                VoiceCommand.STOP_READING -> {
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

                VoiceCommand.PAUSE -> {
                    stopVintern()
                    detectionEnabled = false
                    detectedText = "Đã tạm dừng quan sát"
                    speakMessage(
                        "Đã tạm dừng quan sát.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lumora_pause"
                    )
                }

                VoiceCommand.RESUME -> {
                    if (hasPermission) {
                        hasCapturedResult = false
                        vinternStatus = ""
                        detectionEnabled = true
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
                VoiceCommand.RECAPTURE -> {

                    requestCapture(vinternMode)
                }
                VoiceCommand.REPEAT -> {
                    val textToRepeat = lastReadText
                    if (textToRepeat.isNotBlank() && ttsReady) {
                        speakMessage(
                            "Tôi đọc lại nội dung. $textToRepeat",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "lumora_repeat_ocr"
                        )
                    } else if (ttsReady) {
                        speakMessage(
                            "Chưa có kết quả đọc từ model. Bạn có thể nói đọc văn bản.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "lumora_no_ocr"
                        )
                    }
                }

                VoiceCommand.WAKE -> {
                    if (ttsReady) {
                        speakMessage(
                            "Có tôi đây.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "lumora_wake_reply"
                        )
                    }
                }
                null -> Unit
            }
        }
    }

    VoiceCommandListener(
        enabled = !showDeviceSetup && micPermissionGranted &&
            (manualVoicePending || (alwaysListening &&
                ((!onboardingStarted && !ttsReady) || (onboardingStarted && !onboardingSpeaking)))),
        continuous = alwaysListening,
        ignoreCommandsWhileSpeaking = ignoreVoiceEcho,
        appSpeech = appSpeech,
        onListeningChanged = { voiceListening = it },
        onSessionFinished = { error ->
            manualVoicePending = false
            if (error != null) showVoiceNotice(recognitionErrorMessage(error))
        },
        onCommand = ::handleVoiceCommand
    )

    if (showDeviceSetup) {
        DeviceSetupScreen(
            requirements = deviceRequirements,
            cameraGranted = hasPermission, micGranted = micPermissionGranted,
            modelReady = modelReady, downloading = modelDownloading,
            preparationState = preparationState,
            ttsStatus = ttsStatus,
            ttsReady = ttsReady,
            onTestVoice = { speakMessage("Xin chào. Tôi đang đọc tiếng Việt. Bạn có thể nói đọc văn bản để đọc chữ trước mặt.", TextToSpeech.QUEUE_FLUSH, null, "lumora_voice_test") },
            onTtsSettings = {
                try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: android.content.ActivityNotFoundException) { showVoiceNotice("Máy không mở được cài đặt giọng đọc. Hãy mở trong Cài đặt hệ thống.") }
            },
            ocrBackend = ocrBackend,
            onOcrBackendChange = ocrSettings::select,
            alwaysListening = alwaysListening,
            onVoiceModeChange = { enabled ->
                manualVoicePending = false
                voiceSettings.setAlwaysListening(enabled)
                hostActivity?.syncVoiceService()
                if (enabled && !micPermissionGranted) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
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
                        else -> "Đã tải mô hình. AI đang chuẩn bị; camera và OCR ML Kit vẫn dùng được."
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
                        text = if (vinternBusy) "ĐANG XỬ LÝ ẢNH"
                            else if (hasPermission) "CAMERA TRỰC TIẾP" else "CHƯA CẤP CAMERA",
                        color = accentGreen,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 9.dp
                        )
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { manualVoicePending = false; showDeviceSetup = true },
                enabled = !vinternBusy,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("THIẾT LẬP", color = accentGreen) }

            if (!alwaysListening) {
                Button(
                    onClick = {
                        if (manualVoicePending) {
                            manualVoicePending = false
                        } else if (!micPermissionGranted) {
                            manualVoicePending = true
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            startManualVoice()
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen, contentColor = deepGreen)
                ) { Text(if (manualVoicePending) "Đang nghe… Nhấn để dừng" else "Nhấn để nói") }

            } else {
                Text(if (voiceListening) "Luôn nghe · đang nghe" else "Luôn nghe · đang chờ", modifier = Modifier.weight(1f), color = accentGreen)
            }

            }
            if (voiceStatus.isNotBlank()) Text(voiceStatus, color = accentGreen,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            if (!ttsReady) Text(ttsStatus, color = Color(0xFFFFD6A5), fontSize = 12.sp)

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
                    if (hasPermission) {
                        CameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            onDetection = { result ->
                                detectedText = result
                            },
                            analysisPaused = vinternBusy || !detectionEnabled,
                            vinternCaptureRequest = vinternCaptureRequest,
                            onVinternFrame = { request, frame -> handleCapturedFrame(request, frame) }
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
                                    "Camera đang chuẩn bị"
                                } else {
                                    "Cần cấp quyền camera"
                                },
                                color = white,
                                fontSize = 16.sp
                            )
                            if (!hasPermission) Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Cấp quyền camera")
                            }
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
                    modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (hasCapturedResult) "KẾT QUẢ TỪ ẢNH VỪA CHỤP" else "VẬT THỂ TRONG CAMERA",
                        color = accentGreen,
                        fontSize = 12.sp
                    )

                    Text(
                        text = if (hasCapturedResult) vinternStatus else (speechText ?: detectedText),
                        color = white,
                        fontSize = 16.sp
                    )

                    if (vinternStatus.isNotBlank() && !hasCapturedResult) {
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

            if (modelReady && !vinternBusy && !aiReady) VinternPreparationStatus(preparationState)

            Text(
                "Đọc chữ: nói “đọc văn bản” · OCR: ${ocrBackend.label}",
                color = accentGreen, fontSize = 12.sp
            )
            Button(
                onClick = {
                    when {
                        !modelReady -> { showDeviceSetup = true }
                        preparationState is VinternEngine.PreparationState.Failed ->
                            VinternEngine.prepare(context, retry = true)
                        aiReady -> requestCapture(VinternEngine.Mode.DESCRIBE)
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
                        !modelReady -> "BẬT MÔ TẢ CHI TIẾT"
                        vinternBusy -> "AI ĐANG PHÂN TÍCH..."
                        preparationState is VinternEngine.PreparationState.Failed -> "THỬ CHUẨN BỊ AI LẠI"
                        !aiReady -> "ĐANG CHUẨN BỊ AI…"
                        else -> "CHỤP · MÔ TẢ XUNG QUANH"
                    },
                    fontSize = 16.sp
                )
            }

            if (vinternBusy) {
                OutlinedButton(onClick = { stopVintern() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Dừng phân tích")
                }
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
    continuous: Boolean = true,
    appSpeech: String = "",
    onListeningChanged: (Boolean) -> Unit = {},
    onSessionFinished: (Int?) -> Unit = {},
    onCommand: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCommand by rememberUpdatedState(onCommand)
    val latestOnSessionFinished by rememberUpdatedState(onSessionFinished)
    val latestOnListeningChanged by rememberUpdatedState(onListeningChanged)
    val latestAppSpeech by rememberUpdatedState(appSpeech)
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
    LaunchedEffect(enabled, activityResumed, continuous) {
        if (enabled) {
            if (!activityResumed) {
                latestOnListeningChanged(false)
                if (!continuous) latestOnSessionFinished(null)
            } else if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                latestOnSessionFinished(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }
    DisposableEffect(context, enabled, activityResumed, continuous) {
        if (!enabled || !activityResumed || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            var disposed = false
            var generation = 0L
            var recognizer: SpeechRecognizer? = null
            fun ownerActive() = !disposed &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                (VoiceSettings.get(context).alwaysListening.value == continuous)

            val restartListening = object : Runnable {
                override fun run() {
                    if (!ownerActive()) return
                    val request = ++generation
                    val current = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = current
                    var finished = false
                    var heardSpeech = false
                    val startedDuringSpeech = latestIgnoreCommandsWhileSpeaking
                    fun active() = ownerActive() && generation == request && !finished
                    fun finish(error: Int? = null, command: String? = null, reportError: Boolean = true) {
                        if (!active()) return
                        finished = true
                        latestOnListeningChanged(false)
                        // Release only this session, before a callback can replace it.
                        current.cancel()
                        current.destroy()
                        if (recognizer === current) recognizer = null
                        if (command != null) latestOnCommand(command)
                        else if (reportError) latestOnSessionFinished(error)
                        if (continuous && ownerActive() && generation == request && error !in setOf(9, 12, 13)) {
                            handler.postDelayed(this, if (error in setOf(5, 11)) 1200L else 900L)
                        }
                    }
                    fun handleResults(bundle: Bundle?, partial: Boolean) {
                        if (!active()) return
                        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val spoken = if (partial) matches.firstOrNull { parseExactVoiceCommand(it) != null }.orEmpty()
                            else matches.firstOrNull { parseVoiceCommand(it) != null } ?: matches.firstOrNull().orEmpty()
                        val echo = continuous && (startedDuringSpeech || latestIgnoreCommandsWhileSpeaking)
                        val accepted = acceptVoiceResult(spoken, echo, latestAppSpeech)
                        if (partial && !accepted) return
                        finish(command = spoken.takeIf { accepted },
                            error = if (!continuous && spoken.isBlank()) SpeechRecognizer.ERROR_NO_MATCH else null,
                            reportError = !continuous)
                    }
                    current.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { if (active()) latestOnListeningChanged(true) }
                        override fun onBeginningOfSpeech() { if (active()) heardSpeech = true }
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() { if (active()) latestOnListeningChanged(false) }
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        override fun onResults(results: Bundle?) = handleResults(results, false)
                        override fun onPartialResults(partialResults: Bundle?) = handleResults(partialResults, true)
                        override fun onError(error: Int) {
                            finish(error = error, reportError = !continuous || error !in setOf(6, 7) ||
                                (heardSpeech && !startedDuringSpeech && !latestIgnoreCommandsWhileSpeaking))
                        }
                    })
                    try { current.startListening(intent) }
                    catch (_: Exception) { finish(SpeechRecognizer.ERROR_CLIENT) }
                }
            }
            handler.post(restartListening)
            onDispose {
                disposed = true
                generation++
                latestOnListeningChanged(false)
                handler.removeCallbacks(restartListening)
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
            }
        }
    }
}

/**
 * Chuyển CameraX RGBA_8888 ImageProxy thành Bitmap,
 * có xử lý rowStride/pixelStride.
 */
internal fun imageProxyToBitmap(
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
    val normalized = raw.trim().lowercase()

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

