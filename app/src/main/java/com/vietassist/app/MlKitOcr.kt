package com.vietassist.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Sở hữu ảnh đến khi ML Kit hoàn tất, kể cả khi màn hình đã hủy lượt đọc. */
internal object MlKitOcr {
    suspend fun read(bitmap: Bitmap): VinternEngine.Result = suspendCancellableCoroutine { continuation ->
        val recognizer = try { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        catch (_: Exception) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(VinternEngine.Result.Failed)
            return@suspendCancellableCoroutine
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                val result = try {
                    if (task.isSuccessful) {
                        VinternEngine.Result.Success(task.result.text.trim().ifBlank { "Không thấy chữ." })
                    } else VinternEngine.Result.Failed
                } finally {
                    bitmap.recycle()
                    recognizer.close()
                }
                if (continuation.isActive) continuation.resume(result)
            }
        } catch (_: Exception) {
            bitmap.recycle()
            recognizer.close()
            if (continuation.isActive) continuation.resume(VinternEngine.Result.Failed)
        }
    }
}
