package com.vietassist.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Hàm native trong libvintern.so (app/src/main/cpp/vintern_jni.cpp). */
internal object VinternNative {
    init {
        System.loadLibrary("vintern")
    }

    external fun nativeInit(nativeLibDir: String, cacheDir: String)
    external fun nativeLoad(modelPath: String, mmprojPath: String, nThreads: Int): Boolean
    external fun nativeBackendName(): String
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
 * EfficientDet vẫn đảm nhận nhận diện vật thể liên tục; ML Kit vẫn đảm nhận đọc nhanh.
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

    @Volatile private var nativeReady = false
    @Volatile private var loaded = false
    @Volatile private var cancelRequested = false

    /**
     * Phân tích một khung hình. Hàm sở hữu [bitmap] và giải phóng nó khi xong.
     * Lần gọi đầu tiên tải mô hình vào bộ nhớ nên chậm hơn vài giây.
     */
    suspend fun analyze(context: Context, bitmap: Bitmap, mode: Mode): Result =
        withContext(dispatcher) {
            cancelRequested = false
            try {
                if (VinternRequirements.read(context).inferenceBlocker != null) {
                    return@withContext Result.Failed
                }
                if (!VinternModelManager.isReady(context)) return@withContext Result.ModelMissing
                if (!ensureLoaded(context)) return@withContext Result.Failed

                val scaled = scaleToMaxSide(bitmap, mode.maxSide)
                val rgb = toRgbBytes(scaled)
                val width = scaled.width
                val height = scaled.height
                if (scaled !== bitmap) scaled.recycle()

                val startedAt = System.currentTimeMillis()
                val output = VinternNative.nativeGenerate(
                    rgb, width, height, mode.question, mode.maxTokens
                )
                Log.i(
                    TAG,
                    "Chế độ $mode, ảnh ${width}x$height, " +
                            "mất ${System.currentTimeMillis() - startedAt} ms"
                )
                when {
                    cancelRequested -> Result.Cancelled
                    output == null -> Result.Failed
                    else -> {
                        val text = cleanForSpeech(output.toString(Charsets.UTF_8))
                        if (text.isBlank()) Result.Failed else Result.Success(text)
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Vintern gặp lỗi khi phân tích ảnh", error)
                if (loaded) {
                    VinternNative.nativeUnload()
                    loaded = false
                }
                Result.Failed
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    /** Dừng câu trả lời đang sinh; phần mã hóa ảnh đang chạy sẽ chạy nốt rồi dừng. */
    fun cancel() {
        cancelRequested = true
        if (nativeReady) VinternNative.nativeCancel()
    }

    /** Giải phóng khoảng 1 GB bộ nhớ của mô hình; lần dùng sau sẽ tải lại. */
    fun release() {
        executor.execute {
            if (loaded) {
                VinternNative.nativeUnload()
                loaded = false
            }
        }
    }

    private fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        if (!nativeReady) {
            VinternNative.nativeInit(context.applicationInfo.nativeLibraryDir, context.cacheDir.absolutePath)
            nativeReady = true
        }
        // Dùng nhóm lõi hiệu năng cao; dùng hết lõi thường chậm hơn trên chip big.LITTLE.
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        loaded = VinternNative.nativeLoad(
            VinternModelManager.fileFor(context, VinternModelManager.languageModel).absolutePath,
            VinternModelManager.fileFor(context, VinternModelManager.visionProjector).absolutePath,
            threads
        )
        return loaded
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
