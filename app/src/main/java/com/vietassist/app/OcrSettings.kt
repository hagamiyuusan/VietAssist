package com.vietassist.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OcrBackend(val label: String) { ML_KIT("ML Kit"), VINTERN("Vintern"), LIGHTON("LightOnOCR 0.8B") }

/** Cả Settings và lệnh đọc lấy lựa chọn từ owner này. */
class OcrSettings internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lumora_ocr", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(OcrBackend.valueOf(prefs.getString("backend", OcrBackend.ML_KIT.name)!!))
    val backend = selected.asStateFlow()
    fun select(backend: OcrBackend) {
        prefs.edit().putString("backend", backend.name).apply()
        selected.value = backend
    }
    companion object {
        private var instance: OcrSettings? = null
        @Synchronized fun get(context: Context) = instance ?: OcrSettings(context).also { instance = it }
    }
}

/** Snapshot this choice at capture time; scene descriptions always belong to Vintern. */
internal fun localModelFor(mode: VinternEngine.Mode, backend: OcrBackend): VisionModel? =
    if (mode == VinternEngine.Mode.DESCRIBE) VisionModel.VINTERN else when (backend) {
        OcrBackend.ML_KIT -> null
        OcrBackend.VINTERN -> VisionModel.VINTERN
        OcrBackend.LIGHTON -> VisionModel.LIGHTON
    }
