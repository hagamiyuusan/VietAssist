package com.vietassist.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pinned assets and one downloader owner per model; partial files are resumable. */
open class VisionModelStore(
    private val directory: String,
    val languageModel: ModelFile,
    val visionProjector: ModelFile
) {
    data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)
    private val files = listOf(languageModel, visionProjector)
    val totalBytes: Long = files.sumOf { it.size }
    private val TAG = "LUMORA_MODEL_FILES"
    data class DownloadState(val downloading: Boolean = false, val percent: Int = 0)

    private val mutableDownloadState = MutableStateFlow(DownloadState())
    val downloadState = mutableDownloadState.asStateFlow()
    val isDownloading: Boolean get() = downloadState.value.downloading

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, directory).apply { mkdirs() }

    fun fileFor(context: Context, model: ModelFile): File = File(modelDir(context), model.name)

    /** Chỉ kiểm tra kích thước; SHA256 đã được kiểm tra một lần khi tải xong. */
    fun isReady(context: Context): Boolean =
        files.all { fileFor(context, it).length() == it.size }

    fun requiredDownloadSpace(context: Context): Long {
        val dir = modelDir(context)
        val missingBytes = files.filter { fileFor(context, it).length() != it.size }
            .sumOf { it.size - File(dir, it.name + ".part").length().coerceAtMost(it.size) }
        return if (missingBytes == 0L) 0L else missingBytes + 50L * 1024 * 1024
    }

    /**
     * Tải các file còn thiếu. Hàm chặn luồng; gọi từ luồng nền.
     * [onProgress] nhận phần trăm 0..100 của toàn bộ dung lượng.
     */
    @Throws(IOException::class)
    fun download(context: Context, onProgress: (Int) -> Unit) {
        // Màn hình có thể bị tạo lại khi đang tải; không cho hai luồng cùng ghi một file .part.
        val previous = downloadState.value
        if (previous.downloading || !mutableDownloadState.compareAndSet(previous, DownloadState(downloading = true))) {
            throw IOException("Mô hình đang được tải")
        }
        try {
            VinternRequirements.read(context, this).downloadBlocker?.let { throw IOException(it) }
            downloadMissing(context) { percent ->
                mutableDownloadState.value = DownloadState(downloading = true, percent = percent)
                onProgress(percent)
            }
        } finally {
            mutableDownloadState.value = downloadState.value.copy(downloading = false)
        }
    }

    private fun downloadMissing(context: Context, onProgress: (Int) -> Unit) {
        val dir = modelDir(context)
        if (dir.usableSpace < requiredDownloadSpace(context)) {
            throw IOException("Không đủ dung lượng trống để tải mô hình")
        }

        var doneBefore = 0L
        for (model in files) {
            val target = fileFor(context, model)
            if (target.length() == model.size) {
                doneBefore += model.size
                onProgress((doneBefore * 100 / totalBytes).toInt())
                continue
            }
            val partial = File(dir, model.name + ".part")
            val base = doneBefore
            downloadOne(model, partial) { fileBytes ->
                onProgress(((base + fileBytes) * 100 / totalBytes).toInt())
            }
            if (!sha256Matches(partial, model.sha256)) {
                partial.delete()
                throw IOException("File ${model.name} bị lỗi khi tải, hãy thử lại")
            }
            if (!partial.renameTo(target)) {
                throw IOException("Không lưu được ${model.name}")
            }
            doneBefore += model.size
            Log.i(TAG, "Đã tải xong ${model.name}")
        }
        onProgress(100)
    }

    private fun downloadOne(model: ModelFile, partial: File, onBytes: (Long) -> Unit) {
        var existing = partial.length()
        if (existing > model.size) {
            partial.delete()
            existing = 0L
        }
        if (existing == model.size) return

        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val status = connection.responseCode
            // Máy chủ bỏ qua Range thì tải lại từ đầu.
            val append = existing > 0 && status == HttpURLConnection.HTTP_PARTIAL
            if (status != HttpURLConnection.HTTP_OK && !append) {
                throw IOException("Tải ${model.name} thất bại: HTTP $status")
            }
            var written = if (append) existing else 0L
            var lastReported = -1L
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        // Báo tiến độ mỗi khoảng 4 MB để không làm nghẽn giao diện.
                        if (written - lastReported >= 4L * 1024 * 1024) {
                            lastReported = written
                            onBytes(written)
                        }
                    }
                }
            }
            if (written != model.size) {
                throw IOException("Tải ${model.name} chưa đủ ($written/${model.size} byte)")
            }
            onBytes(written)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Matches(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }
}

    private val vinternLanguage = VisionModelStore.ModelFile(
        name = "Vintern-1B-v3_5-Q4_K_M.gguf",
        url = "https://huggingface.co/rootonchair/Vintern-1B-v3_5-GGUF-ext/resolve/" +
                "0b8ac52a1adcaaaff9dae65c4322c1a7a14bc8b6/Vintern-1B-v3_5-Q4_K_M.gguf",
        size = 490_981_280L,
        sha256 = "ea819280ca63ccc40ec4b961950bdff800b13be2b1ea59ffd34337889f241fca"
    )

    private val vinternVision = VisionModelStore.ModelFile(
        name = "mmproj-Vintern-1B-v3_5-Q8_0.gguf",
        url = "https://huggingface.co/ngxson/Vintern-1B-v3_5-GGUF/resolve/" +
                "15b1d6df01122fe3bdf45c1d6c644ce4a2935c6a/mmproj-Vintern-1B-v3_5-Q8_0.gguf",
        size = 332_567_520L,
        sha256 = "2dc8cab1a5a0b51b37c5f5727a3ababa1ac9521456b0e00f93d66730cdd99b37"
    )


private val lightOnLanguage = VisionModelStore.ModelFile(
    "LightOnOCR-3-0.8B-Q4_K_M.gguf",
    "https://huggingface.co/richardyoung/LightOnOCR-3-0.8B-GGUF/resolve/5901dcc106d24a337ec0762a072cd58b2c330fa6/LightOnOCR-3-0.8B-Q4_K_M.gguf",
    529_297_632L, "f4d1f3148101fb75337b811379f54c45f40e227f176fb47c0e2712e337bfa4fe"
)
private val lightOnVision = VisionModelStore.ModelFile(
    "mmproj-LightOnOCR-3-0.8B-F16.gguf",
    "https://huggingface.co/richardyoung/LightOnOCR-3-0.8B-GGUF/resolve/5901dcc106d24a337ec0762a072cd58b2c330fa6/mmproj-LightOnOCR-3-0.8B-F16.gguf",
    204_986_944L, "8d9c237ec8ac54b73f17f760f452b5d058c3ab472391ccaa0131705744017cdb"
)

object VinternModelManager : VisionModelStore("vintern", vinternLanguage, vinternVision)
object LightOnModelManager : VisionModelStore("lighton", lightOnLanguage, lightOnVision)
