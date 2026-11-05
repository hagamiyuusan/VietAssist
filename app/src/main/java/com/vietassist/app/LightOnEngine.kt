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

enum class VisionModel(val label: String) {
    LIGHTON("LightOnOCR 0.8B");
    val files: VisionModelStore get() = when (this) {
        LIGHTON -> LightOnModelManager
    }
}

/** Hàm native trong liblighton.so (app/src/main/cpp/lighton_jni.cpp). */
internal object LightOnNative {
    init {
        System.loadLibrary("lighton")
    }

    external fun nativeGpuAvailable(nativeLibDir: String): Boolean
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
 * Một executor sở hữu runtime và một model tại một thời điểm.
 * LightOnOCR chỉ chép chữ; ML Kit là OCR mặc định.
 */
object LightOnEngine {
    private const val TAG = "LUMORA_LIGHTON"

    enum class Mode { DESCRIBE, READ }

    sealed class Result {
        data class Success(val text: String) : Result()
        object ModelMissing : Result()
        object Failed : Result()
        object Cancelled : Result()
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lumora-lighton")
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
    fun preparationFor(model: VisionModel) = preparationState
    private fun stateFor(model: VisionModel) = mutablePreparationState
    private var loadedModel: VisionModel? = null
    @Volatile private var nativeReady = false
    @Volatile private var activeCancellation: AtomicLong? = null

    /** Tác vụ thuộc tiến trình ứng dụng, không bị hủy khi màn hình đóng/đổi cấu hình. */
    fun prepare(context: Context, retry: Boolean = false, model: VisionModel = VisionModel.LIGHTON): Deferred<Boolean> {
        val completion = CompletableDeferred<Boolean>()
        val appContext = context.applicationContext
        executor.execute {
            try {
                completion.complete(prepareOnWorker(appContext, retry, model))
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    /** Sở hữu bitmap kể cả khi coroutine bị hủy trước lúc được chạy trên executor. */
    suspend fun analyze(context: Context, bitmap: Bitmap, mode: Mode, cancellation: AtomicLong, model: VisionModel = VisionModel.LIGHTON): Result {
        val request = cancellation.get()
        try {
            return withContext(dispatcher) {
                if (mode != Mode.READ) return@withContext Result.Failed
                if (request != cancellation.get()) return@withContext Result.Cancelled
                if (LightOnRequirements.read(context, model.files).inferenceBlocker != null) return@withContext Result.Failed
                if (!model.files.isReady(context)) return@withContext Result.ModelMissing
                if (!prepareOnWorker(context.applicationContext, model = model)) return@withContext Result.Failed
                if (request != cancellation.get()) return@withContext Result.Cancelled

                try {
                    val scaled = scaleToMaxSide(bitmap, 2048)
                    val width = scaled.width
                    val height = scaled.height
                    val rgb = try { toRgbBytes(scaled) } finally {
                        if (scaled !== bitmap) scaled.recycle()
                    }
                    val startedAt = System.currentTimeMillis()
                    if (request != cancellation.get()) return@withContext Result.Cancelled
                    activeCancellation = cancellation
                    val output = try {
                        LightOnNative.nativeGenerate(rgb, width, height, "",
                            2048)
                    } finally {
                        activeCancellation = null
                    }
                    Log.i(TAG, "Model ${model.name}, chế độ $mode, ảnh ${width}x$height, mất ${System.currentTimeMillis() - startedAt} ms")
                    when {
                        request != cancellation.get() -> Result.Cancelled
                        output == null -> {
                            failPreparation(model, "AI gặp lỗi. Hãy thử chuẩn bị AI lại.")
                            Result.Failed
                        }
                        else -> {
                            val text = cleanForSpeech(output.toString(Charsets.UTF_8))
                            if (text.isBlank()) Result.Failed else Result.Success(text)
                        }
                    }
                } catch (error: Throwable) {
                    failPreparation(model, "AI gặp lỗi. Hãy thử chuẩn bị AI lại.", error)
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
        if (nativeReady && activeCancellation === cancellation) LightOnNative.nativeCancel()
    }

    /** Giải phóng chủ động khi cần; đóng Activity không làm mất model đã chuẩn bị. */
    fun release() {
        executor.execute {
            try {
                if (nativeReady) LightOnNative.nativeUnload()
            } finally {
                loadedModel = null
                mutablePreparationState.value = PreparationState.NotLoaded
            }
        }
    }

    // Mọi đường nạp, làm nóng và giải phóng đều chạy trên cùng executor.
    private fun prepareOnWorker(context: Context, retry: Boolean = false, model: VisionModel = VisionModel.LIGHTON): Boolean {
        val state = stateFor(model)
        val store = model.files
        if (loadedModel == model && state.value == PreparationState.Ready) return true
        if (state.value is PreparationState.Failed && !retry) return false
        val blocker = LightOnRequirements.read(context, model.files).inferenceBlocker
        if (blocker != null) return failPreparation(model, blocker)
        if (!model.files.isReady(context)) return failPreparation(model, "Chưa tải đủ mô hình AI.")
        loadedModel?.let { stateFor(it).value = PreparationState.NotLoaded }
        loadedModel = null
        state.value = PreparationState.Preparing
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            if (!nativeReady) {
                LightOnNative.nativeInit(context.applicationInfo.nativeLibraryDir, context.cacheDir.absolutePath)
                nativeReady = true
            }
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            check(LightOnNative.nativeLoad(
                store.fileFor(context, store.languageModel).absolutePath,
                store.fileFor(context, store.visionProjector).absolutePath,
                threads
            )) { "native-load-failed" }
            if (model == VisionModel.LIGHTON) {
                check(LightOnNative.nativeBackendName() == "OpenCL") { "lighton-requires-gpu" }
            }
            Log.i(TAG, "Nạp model hoàn tất trong ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            // Chuẩn bị encoder và một bước ngôn ngữ, không chạy lại toàn bộ
            // prefill ảnh/câu hỏi của một lượt phân tích bỏ đi.
            check(LightOnNative.nativeWarmup()) { "native-warmup-failed" }
            loadedModel = model
            state.value = PreparationState.Ready
            Log.i(TAG, "Chuẩn bị AI hoàn tất trong ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            return true
        } catch (error: Throwable) {
            return failPreparation(model, "Chưa chuẩn bị được AI. Hãy kiểm tra thiết bị rồi thử lại.", error)
        }
    }

    private fun failPreparation(model: VisionModel, message: String, error: Throwable? = null): Boolean {
        if (error != null) Log.e(TAG, "Chuẩn bị AI thất bại: ${error.javaClass.simpleName}")
        try {
            if (nativeReady) LightOnNative.nativeUnload()
        } finally {
            loadedModel?.let { stateFor(it).value = PreparationState.NotLoaded }
            loadedModel = null
            stateFor(model).value = PreparationState.Failed(message)
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
