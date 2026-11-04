package com.vietassist.app

import android.speech.tts.TextToSpeech
import java.util.Locale

internal fun vietnameseVoiceReady(languageStatus: Int, voiceLanguage: String?, needsData: Boolean): Boolean =
    languageStatus >= 0 && voiceLanguage == "vi" && !needsData

/** Một chính sách giọng Việt cho cả Activity và câu trả lời của service nghe nền. */
internal object VietnameseSpeech {
    fun configure(engine: TextToSpeech): Boolean {
        val status = engine.setLanguage(Locale.forLanguageTag("vi-VN"))
        val voice = engine.voices.orEmpty()
            .filter { it.locale.language == "vi" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
            .firstOrNull()
        if (voice != null && engine.setVoice(voice) != TextToSpeech.SUCCESS) return false
        engine.setSpeechRate(0.9f)
        engine.setPitch(1f)
        val selected = engine.voice
        return vietnameseVoiceReady(status, selected?.locale?.language,
            selected == null || TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in selected.features.orEmpty())
    }
}
