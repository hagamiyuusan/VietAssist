package com.vietassist.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Hàm native trong libvintern.so (app/src/main/cpp/vintern_jni.cpp). */
internal object VinternNative {
    init {
        System.loadLibrary("vintern")
    }

    external fun nativeInit(nativeLibDir: String, cacheDir: String)
    external fun nativeLoad(modelPath: String, mmprojPath: String, nThreads: Int): Boolean
    external fun nativeBackendName(): String
    external fun nativeWarmup(): Boolean
    external fun nativeGenerate(
        rgb: ByteArray,
        width: Int,
        height: Int,
        question: String,
        maxTokens: Int
    ): ByteArray?
    external fun nativeCancel()
    external fun nativeUnload()
}

/**
 * Chạy Vintern-1B-v3.5 trên máy cho hai lệnh chụp một lần:
 * mô tả xung quanh và đọc chi tiết tài liệu.
 * EfficientDet đảm nhận nhận diện vật thể liên tục; cả mô tả và đọc chữ dùng model này.
 */
object VinternEngine {
    private const val TAG = "LUMORA_VINTERN"

    // Ảnh không quá 448 px chỉ tạo 1 ô (256 token ảnh); không quá 896 px tạo tối đa
    // 2x2 ô và một ảnh tổng quan, đúng giới hạn max_dynamic_patch = 4 của Vintern.
    enum class Mode(val maxSide: Int, val maxTokens: Int, val question: String) {
        DESCRIBE(
            maxSide = 448,
            maxTokens = 160,
            question = "Hãy mô tả ngắn gọn cảnh trong ảnh cho người khiếm thị, tối đa ba câu. " +
                    "Nêu các vật thể chính và vị trí của chúng như bên trái, bên phải, phía trước. " +
                    "Nếu có chữ to dễ đọc thì đọc ra. Không đoán những gì không nhìn rõ."
        ),
        READ(
            maxSide = 896,
            maxTokens = 512,
            question = "Hãy đọc chính xác toàn bộ chữ trong ảnh theo thứ tự từ trên xuống dưới. " +
                    "Chỉ ghi lại chữ có trong ảnh, không giải thích và không thêm nội dung. " +
                    "Nếu không có chữ, trả lời: Không thấy chữ."
        )
    }

    sealed class Result {
        data class Success(val text: String) : Result()
        object ModelMissing : Result()
        object Failed : Result()
        object Cancelled : Result()
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lumora-vintern")
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    sealed interface PreparationState {
        data object NotLoaded : PreparationState
        data object Preparing : PreparationState
        data object Ready : PreparationState
        data class Failed(val message: String) : PreparationState
    }

    private val mutablePreparationState = MutableStateFlow<PreparationState>(PreparationState.NotLoaded)
    val preparationState = mutablePreparationState.asStateFlow()
    @Volatile private var nativeReady = false
    @Volatile private var activeCancellation: AtomicLong? = null

    /** Tác vụ thuộc tiến trình ứng dụng, không bị hủy khi màn hình đóng/đổi cấu hình. */
    fun prepare(context: Context, retry: Boolean = false): Deferred<Boolean> {
        val completion = CompletableDeferred<Boolean>()
        val appContext = context.applicationContext
        executor.execute {
            try {
                completion.complete(prepareOnWorker(appContext, retry))
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    /** Sở hữu bitmap kể cả khi coroutine bị hủy trước lúc được chạy trên executor. */
    suspend fun analyze(context: Context, bitmap: Bitmap, mode: Mode, cancellation: AtomicLong): Result {
        val request = cancellation.get()
        try {
            return withContext(dispatcher) {
                if (request != cancellation.get()) return@withContext Result.Cancelled
                if (VinternRequirements.read(context).inferenceBlocker != null) return@withContext Result.Failed
                if (!VinternModelManager.isReady(context)) return@withContext Result.ModelMissing
                if (!prepareOnWorker(context.applicationContext)) return@withContext Result.Failed
                if (request != cancellation.get()) return@withContext Result.Cancelled

                try {
                    val scaled = scaleToMaxSide(bitmap, mode.maxSide)
                    val width = scaled.width
                    val height = scaled.height
                    val rgb = try { toRgbBytes(scaled) } finally {
                        if (scaled !== bitmap) scaled.recycle()
                    }
                    val startedAt = System.currentTimeMillis()
                    if (request != cancellation.get()) return@withContext Result.Cancelled
                    activeCancellation = cancellation
                    val output = try {
                        VinternNative.nativeGenerate(rgb, width, height, mode.question, mode.maxTokens)
                    } finally {
                        activeCancellation = null
                    }
                    Log.i(TAG, "Chế độ $mode, ảnh ${width}x$height, mất ${System.currentTimeMillis() - startedAt} ms")
                    when {
                        request != cancellation.get() -> Result.Cancelled
                        output == null -> {
                            failPreparation("AI gặp lỗi. Hãy thử chuẩn bị AI lại.")
                            Result.Failed
                        }
                        else -> {
                            val text = cleanForSpeech(output.toString(Charsets.UTF_8))
                            if (text.isBlank()) Result.Failed else Result.Success(text)
                        }
                    }
                } catch (error: Throwable) {
                    failPreparation("AI gặp lỗi. Hãy thử chuẩn bị AI lại.", error)
                    Result.Failed
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Chỉ hủy yêu cầu phân tích; không hủy bước chuẩn bị AI dùng chung cho tiến trình. */
    fun cancel(cancellation: AtomicLong) {
        cancellation.incrementAndGet()
        if (nativeReady && activeCancellation === cancellation) VinternNative.nativeCancel()
    }

    /** Giải phóng chủ động khi cần; đóng Activity không làm mất model đã chuẩn bị. */
    fun release() {
        executor.execute {
            try {
                if (nativeReady) VinternNative.nativeUnload()
            } finally {
                mutablePreparationState.value = PreparationState.NotLoaded
            }
        }
    }

    // Mọi đường nạp, làm nóng và giải phóng đều chạy trên cùng executor.
    private fun prepareOnWorker(context: Context, retry: Boolean = false): Boolean {
        if (preparationState.value == PreparationState.Ready) return true
        if (preparationState.value is PreparationState.Failed && !retry) return false
        val blocker = VinternRequirements.read(context).inferenceBlocker
        if (blocker != null) return failPreparation(blocker)
        if (!VinternModelManager.isReady(context)) return failPreparation("Chưa tải đủ mô hình AI.")
        mutablePreparationState.value = PreparationState.Preparing
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            if (!nativeReady) {
                VinternNative.nativeInit(context.applicationInfo.nativeLibraryDir, context.cacheDir.absolutePath)
                nativeReady = true
            }
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            check(VinternNative.nativeLoad(
                VinternModelManager.fileFor(context, VinternModelManager.languageModel).absolutePath,
                VinternModelManager.fileFor(context, VinternModelManager.visionProjector).absolutePath,
                threads
            )) { "native-load-failed" }
            Log.i(TAG, "Nạp model hoàn tất trong ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            // Chuẩn bị encoder và một bước ngôn ngữ, không chạy lại toàn bộ
            // prefill ảnh/câu hỏi của một lượt phân tích bỏ đi.
            check(VinternNative.nativeWarmup()) { "native-warmup-failed" }
            mutablePreparationState.value = PreparationState.Ready
            Log.i(TAG, "Chuẩn bị AI hoàn tất trong ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            return true
        } catch (error: Throwable) {
            return failPreparation("Chưa chuẩn bị được AI. Hãy kiểm tra thiết bị rồi thử lại.", error)
        }
    }

    private fun failPreparation(message: String, error: Throwable? = null): Boolean {
        if (error != null) Log.e(TAG, "Chuẩn bị AI thất bại: ${error.javaClass.simpleName}")
        try {
            if (nativeReady) VinternNative.nativeUnload()
        } finally {
            mutablePreparationState.value = PreparationState.Failed(message)
        }
        return false
    }

    private fun scaleToMaxSide(source: Bitmap, maxSide: Int): Bitmap {
        val longSide = maxOf(source.width, source.height)
        if (longSide <= maxSide) return source
        val ratio = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun toRgbBytes(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val color = pixels[i]
            rgb[i * 3] = (color shr 16 and 0xFF).toByte()
            rgb[i * 3 + 1] = (color shr 8 and 0xFF).toByte()
            rgb[i * 3 + 2] = (color and 0xFF).toByte()
        }
        return rgb
    }

    // Bỏ ký hiệu Markdown để giọng đọc không đọc ra dấu sao, dấu thăng hay gạch bảng.
    private fun cleanForSpeech(raw: String): String =
        raw.lines()
            .map { line ->
                line.replace(Regex("[*#`_>]+"), " ")
                    .replace(Regex("^\\s*[-•]\\s+"), "")
                    .replace("|", ", ")
                    .replace(Regex("\\s+"), " ")
                    .trim(' ', ',')
            }
            .filter { it.isNotBlank() && !it.matches(Regex("[-:, ]+")) }
            .joinToString(". ")
            .replace(Regex("([.!?:;])\\s*\\.\\s"), "$1 ")
            .trim()
}
