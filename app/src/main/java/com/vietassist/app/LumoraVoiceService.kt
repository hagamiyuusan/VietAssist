package com.vietassist.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

/**
 * Dịch vụ nghe cụm đánh thức khi Activity không còn ở phía trước.
 * Activity là nơi xử lý lệnh đầy đủ; dịch vụ này chỉ nghe "LUMORA ơi" /
 * "Trợ lý ơi", dừng nghe trước khi thử mở Activity để tránh hai recognizer
 * cùng sử dụng micro.
 */
class LumoraVoiceService : Service() {
    companion object {
        const val ACTION_INIT = "com.vietassist.app.voice.INIT"
        const val ACTION_LISTEN_BACKGROUND = "com.vietassist.app.voice.LISTEN_BACKGROUND"
        const val ACTION_STOP_BACKGROUND = "com.vietassist.app.voice.STOP_BACKGROUND"
        private const val CHANNEL_ID = "lumora_voice_service"
        private const val NOTIFICATION_ID = 7314
        private const val TAG = "LUMORA_BG_VOICE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var backgroundListening = false
    private var foregroundStarted = false
    private var wakeHandled = false
    private var wakeTts: TextToSpeech? = null
    private var wakeLaunchStarted = false

    private val launchAfterWakeTask = Runnable { launchActivityAfterWake() }
    private val restartTask = Runnable { startRecognitionSession() }
    // Nếu Android âm thầm chặn việc mở Activity, tiếp tục chờ câu gọi sau vài giây.
    // Khi Activity thực sự mở, ACTION_INIT sẽ hủy tác vụ này.
    private val wakeFallbackTask = Runnable {
        if (!backgroundListening && hasMicrophonePermission()) {
            backgroundListening = true
            wakeHandled = false
            updateNotification("LUMORA vẫn đang chờ câu gọi; chạm thông báo nếu cần mở ứng dụng")
            startRecognitionSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_INIT

        if (!hasMicrophonePermission()) {
            Log.w(TAG, "Thiếu quyền micro; dừng dịch vụ nghe nền")
            stopBackgroundRecognition()
            stopSelf()
            return START_NOT_STICKY
        }

        // Dịch vụ phải được khởi động khi Activity còn hiển thị, sau khi người
        // dùng cấp quyền micro. Không tự khởi chạy dịch vụ microphone từ nền.
        try {
            ensureForegroundNotification()
        } catch (error: Exception) {
            Log.e(TAG, "Không thể chạy dịch vụ foreground", error)
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_INIT, ACTION_STOP_BACKGROUND -> {
                handler.removeCallbacks(wakeFallbackTask)
                backgroundListening = false
                stopBackgroundRecognition()
                updateNotification("LUMORA sẵn sàng; đang ở trong ứng dụng")
            }
            ACTION_LISTEN_BACKGROUND -> {
                backgroundListening = true
                wakeHandled = false
                updateNotification("LUMORA đang chờ câu gọi khi chạy nền")
                startRecognitionSession()
            }
        }
        return START_STICKY
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensureForegroundNotification() {
        if (foregroundStarted) return
        val notification = buildNotification("LUMORA sẵn sàng nghe câu gọi khi chạy nền")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LUMORA nghe gọi nền",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo khi LUMORA đang chờ câu gọi bằng giọng nói"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            7315,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LUMORA")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        if (!foregroundStarted) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun startRecognitionSession() {
        handler.removeCallbacks(restartTask)
        if (!backgroundListening || wakeHandled || !hasMicrophonePermission()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer không khả dụng trên thiết bị")
            updateNotification("Không thể nghe nền trên thiết bị này; chạm để mở LUMORA")
            handler.postDelayed(restartTask, 3000L)
            return
        }

        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speechRecognizer ->
                    speechRecognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) {
                            if (!backgroundListening || wakeHandled) return
                            val partials = partialResults?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            if (partials.orEmpty().any { isWakePhrase(it) }) {
                                Log.d(TAG, "Nhận ra câu gọi từ kết quả tạm thời")
                                handleWakePhrase()
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit

                        override fun onResults(results: Bundle?) {
                            if (!backgroundListening || wakeHandled) return
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val phrase = matches?.joinToString(" | ").orEmpty()
                            Log.d(TAG, "Các kết quả nghe nền: $phrase")
                            if (matches.orEmpty().any { isWakePhrase(it) }) {
                                handleWakePhrase()
                            } else {
                                handler.postDelayed(restartTask, 450L)
                            }
                        }

                        override fun onError(error: Int) {
                            if (!backgroundListening || wakeHandled) return
                            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                updateNotification("Micro chưa được cấp quyền; chạm để mở LUMORA")
                                return
                            }
                            handler.postDelayed(
                                restartTask,
                                if (error == SpeechRecognizer.ERROR_CLIENT) 1200L else 650L
                            )
                        }
                    })
                }
            }

            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer?.startListening(speechIntent)
        } catch (error: Exception) {
            Log.w(TAG, "Không thể bắt đầu phiên nghe nền", error)
            handler.postDelayed(restartTask, 1200L)
        }
    }

    private fun isWakePhrase(spoken: String): Boolean {
        val normalized = Normalizer.normalize(
            spoken.lowercase(Locale.forLanguageTag("vi-VN")),
            Normalizer.Form.NFD
        ).replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd')
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.contains("lumora oi") ||
                normalized.contains("lu mora oi") ||
                normalized.contains("lu mo ra oi") ||
                normalized.contains("lumo ra oi") ||
                normalized.contains("tro ly oi")
    }

    private fun handleWakePhrase() {
        if (wakeHandled) return
        wakeHandled = true
        backgroundListening = false
        stopBackgroundRecognition()
        wakeLaunchStarted = false
        handler.removeCallbacks(launchAfterWakeTask)
        updateNotification("LUMORA đã nghe thấy bạn; đang trả lời và mở ứng dụng")

        // Hai việc được khởi động gần như đồng thời:
        // 1) gửi lệnh mở giao diện ngay, không đợi TTS;
        // 2) phát câu trả lời bằng TTS ở dịch vụ.
        // Android/Samsung vẫn có thể chặn Activity launch khi ứng dụng ở nền.
        launchActivityAfterWake()

        try {
            wakeTts?.stop()
            wakeTts?.shutdown()
            wakeTts = TextToSpeech(applicationContext) { status ->
                val engine = wakeTts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    try {
                        val languageResult = engine.setLanguage(Locale.forLanguageTag("vi-VN"))
                        engine.setSpeechRate(0.9f)
                        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            Log.w(TAG, "TTS tiếng Việt không khả dụng trên thiết bị")
                            updateNotification("Đã nghe câu gọi; giọng tiếng Việt chưa sẵn sàng")
                            return@TextToSpeech
                        }
                        val result = engine.speak(
                            "Dạ, tôi đây. Tôi đang mở ứng dụng.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "lumora_background_wake_reply"
                        )
                        if (result != TextToSpeech.SUCCESS) {
                            Log.w(TAG, "TTS không nhận được câu trả lời")
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Không thể phát câu trả lời gọi nền", error)
                    }
                } else {
                    Log.w(TAG, "Không khởi tạo được TTS: $status")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Không thể khởi tạo TTS phản hồi câu gọi", error)
        }
    }

    private fun launchActivityAfterWake() {
        if (wakeLaunchStarted) return
        wakeLaunchStarted = true
        handler.removeCallbacks(launchAfterWakeTask)
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("lumora_wake_detected", true)
                putExtra("lumora_wake_reply_done", false)
            }
            startActivity(launchIntent)
        } catch (error: Exception) {
            Log.w(TAG, "Android không cho phép mở Activity từ nền", error)
            updateNotification("LUMORA đã nghe câu gọi; nếu màn hình chưa mở, chạm thông báo để vào ứng dụng")
        }
        // Nếu Android chặn âm thầm, dịch vụ tiếp tục chờ câu gọi sau vài giây.
        handler.removeCallbacks(wakeFallbackTask)
        handler.postDelayed(wakeFallbackTask, 3500L)
    }

    private fun stopBackgroundRecognition() {
        handler.removeCallbacks(restartTask)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (error: Exception) {
            Log.w(TAG, "Lỗi khi dừng recognizer", error)
        }
        recognizer = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Không dừng dịch vụ chỉ vì người dùng vuốt Activity khỏi màn hình gần đây.
        // Hệ điều hành vẫn có thể kết thúc dịch vụ hoặc giới hạn nhận dạng giọng nói.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        backgroundListening = false
        stopBackgroundRecognition()
        handler.removeCallbacksAndMessages(null)
        try {
            wakeTts?.stop()
            wakeTts?.shutdown()
        } catch (_: Exception) { }
        wakeTts = null
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
