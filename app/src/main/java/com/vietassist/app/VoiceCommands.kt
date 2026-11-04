package com.vietassist.app

import java.text.Normalizer
import java.util.Locale

enum class VoiceCommand(val label: String) {
    STOP_READING("Dừng đọc"), PAUSE("Tạm dừng quan sát"), RESUME("Quan sát trực tiếp"),
    RECAPTURE("Chụp lại"), REPEAT("Đọc lại"), HELP("Hướng dẫn"), DOWNLOAD("Tải model"),
    DESCRIBE("Mô tả xung quanh"), READ("Đọc văn bản"), WAKE("Gọi trợ lý")
}

private val aliases = linkedMapOf(
    VoiceCommand.STOP_READING to listOf("dung doc van ban", "dung doc", "ngung doc"),
    VoiceCommand.PAUSE to listOf("tam dung quan sat", "dung quan sat", "tam dung"),
    VoiceCommand.RESUME to listOf("quay lai quan sat", "bat dau quan sat", "quan sat truc tiep", "quan sat"),
    VoiceCommand.RECAPTURE to listOf("nhan dien lai", "chup lai", "doc phan khac"),
    VoiceCommand.REPEAT to listOf("doc lai"),
    VoiceCommand.HELP to listOf("huong dan su dung", "huong dan lai", "huong dan"),
    VoiceCommand.DOWNLOAD to listOf("tai mo hinh"),
    VoiceCommand.DESCRIBE to listOf("mo ta", "xung quanh co gi", "truoc mat co gi", "chup"),
    VoiceCommand.READ to listOf("doc chi tiet", "doc ky", "doc van ban", "doc tai lieu", "hay doc", "doc chu"),
    VoiceCommand.WAKE to listOf("lumora oi", "lu mora oi", "lu mo ra oi", "lumo ra oi", "tro ly oi")
)

internal fun normalizeVoiceText(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')
    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

fun parseVoiceCommand(text: String): VoiceCommand? {
    val normalized = " ${normalizeVoiceText(text)} "
    return aliases.entries.firstOrNull { (_, values) -> values.any { " $it " in normalized } }?.key
}

internal fun parseExactVoiceCommand(text: String): VoiceCommand? {
    val normalized = normalizeVoiceText(text)
    return aliases.entries.firstOrNull { normalized in it.value }?.key
}

/** Cho phép ngắt lời bằng lệnh chính xác, nhưng không nhận lại câu app vừa nói. */
internal fun acceptVoiceResult(spoken: String, mayContainEcho: Boolean, appSpeech: String): Boolean {
    if (spoken.isBlank()) return false
    if (!mayContainEcho) return true
    val normalized = normalizeVoiceText(spoken)
    val exact = aliases.values.any { normalized in it }
    return exact && " $normalized " !in " ${normalizeVoiceText(appSpeech)} "
}

internal fun recognitionErrorMessage(error: Int): String = when (error) {
    6, 7 -> "Chưa nghe rõ lệnh. Bạn hãy nói lại."
    1, 2 -> "Bộ nghe đang lỗi mạng. Kiểm tra kết nối rồi nói lại."
    9 -> "Chưa có quyền micro. Mở Thiết lập để cấp quyền."
    12, 13 -> "Bộ nghe chưa sẵn sàng cho tiếng Việt. Kiểm tra cài đặt giọng nói."
    else -> "Bộ nghe gặp lỗi ($error). Kiểm tra cài đặt giọng nói rồi thử lại."
}
