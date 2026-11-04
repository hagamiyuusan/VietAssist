package com.vietassist.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nguồn duy nhất cho lựa chọn nghe, dùng chung bởi UI và service. */
class VoiceSettings internal constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("lumora_voice", Context.MODE_PRIVATE)
    private val mutableAlwaysListening = MutableStateFlow(preferences.getBoolean("always_listening", false))
    val alwaysListening = mutableAlwaysListening.asStateFlow()

    fun setAlwaysListening(enabled: Boolean) {
        preferences.edit().putBoolean("always_listening", enabled).apply()
        mutableAlwaysListening.value = enabled
    }

    companion object {
        private var instance: VoiceSettings? = null
        @Synchronized fun get(context: Context): VoiceSettings =
            instance ?: VoiceSettings(context).also { instance = it }
    }
}

/** Dùng chung cho mọi điểm tự mở micro, bao gồm khởi động lại service. */
internal fun automaticListeningAllowed(enabled: Boolean, microphoneGranted: Boolean): Boolean =
    enabled && microphoneGranted
