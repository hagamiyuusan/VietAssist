package com.vietassist.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OcrBackend(val label: String) { ML_KIT("ML Kit"), VINTERN("Vintern") }

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

internal fun usesVintern(mode: VinternEngine.Mode, backend: OcrBackend): Boolean =
    mode == VinternEngine.Mode.DESCRIBE || backend == OcrBackend.VINTERN
