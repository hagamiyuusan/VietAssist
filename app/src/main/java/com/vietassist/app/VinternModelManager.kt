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

/**
 * Quản lý file mô hình Vintern-1B-v3.5 (GGUF) cho LUMORA.
 * - Mô hình không nằm trong APK; chỉ tải khi người dùng yêu cầu "tải mô hình".
 * - Link Hugging Face cố định theo commit, kiểm tra kích thước và SHA256 sau khi tải.
 * - Tải dở được lưu dạng .part và tải tiếp ở lần sau.
 *
 * Bản ngôn ngữ dùng Q4_K_M cho nhẹ; bộ mã hóa ảnh giữ Q8_0 vì lượng tử thấp hơn
 * làm giảm rõ chất lượng đọc chữ.
 */
object VinternModelManager {
    private const val TAG = "LUMORA_VINTERN"

    data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

    val languageModel = ModelFile(
        name = "Vintern-1B-v3_5-Q4_K_M.gguf",
        url = "https://huggingface.co/rootonchair/Vintern-1B-v3_5-GGUF-ext/resolve/" +
                "0b8ac52a1adcaaaff9dae65c4322c1a7a14bc8b6/Vintern-1B-v3_5-Q4_K_M.gguf",
        size = 490_981_280L,
        sha256 = "ea819280ca63ccc40ec4b961950bdff800b13be2b1ea59ffd34337889f241fca"
    )

    val visionProjector = ModelFile(
        name = "mmproj-Vintern-1B-v3_5-Q8_0.gguf",
        url = "https://huggingface.co/ngxson/Vintern-1B-v3_5-GGUF/resolve/" +
                "15b1d6df01122fe3bdf45c1d6c644ce4a2935c6a/mmproj-Vintern-1B-v3_5-Q8_0.gguf",
        size = 332_567_520L,
        sha256 = "2dc8cab1a5a0b51b37c5f5727a3ababa1ac9521456b0e00f93d66730cdd99b37"
    )

    private val files = listOf(languageModel, visionProjector)
    val totalBytes: Long = files.sumOf { it.size }

    data class DownloadState(val downloading: Boolean = false, val percent: Int = 0)

    private val mutableDownloadState = MutableStateFlow(DownloadState())
    val downloadState = mutableDownloadState.asStateFlow()
    val isDownloading: Boolean get() = downloadState.value.downloading

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "vintern").apply { mkdirs() }

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
            VinternRequirements.read(context).downloadBlocker?.let { throw IOException(it) }
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
