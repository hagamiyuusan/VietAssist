package com.vietassist.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechDiagnosticsTest {
    @Test fun vietnameseVoiceMustActuallyBeVietnamese() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val initialized = CountDownLatch(1)
        var engine: TextToSpeech? = null
        var init = TextToSpeech.ERROR
        instrumentation.runOnMainSync {
            engine = TextToSpeech(context, { status -> init = status; initialized.countDown() }, InstrumentationRegistry.getArguments().getString("engine"))
        }
        try {
            assertTrue("TTS init timed out", initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, init)
            val tts = engine!!
            assertTrue("Vietnamese voice configuration failed", VietnameseSpeech.configure(tts))
            val status = tts.isLanguageAvailable(Locale.forLanguageTag("vi-VN"))
            val vietnamese = tts.voices.orEmpty().filter { it.locale.language == "vi" }
            println("SPEECH_PROBE ttsEngine=${tts.defaultEngine} viStatus=$status currentLocale=${tts.voice?.locale?.toLanguageTag()} viVoices=${vietnamese.size}")
            println("SPEECH_PROBE voiceName=${tts.voice?.name} network=${tts.voice?.isNetworkConnectionRequired}")
            val services = context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), PackageManager.MATCH_ALL)
            println("SPEECH_PROBE visibleRecognizers=${services.map { it.serviceInfo.packageName }.distinct().sorted().joinToString(",")}")
            try {
                val service = context.packageManager.getServiceInfo(ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"), PackageManager.MATCH_DISABLED_COMPONENTS)
                println("SPEECH_PROBE googleAsrEnabled=${service.enabled} exported=${service.exported}")
            } catch (_: PackageManager.NameNotFoundException) { println("SPEECH_PROBE googleAsrVisible=false") }
            assertEquals("Active TTS voice must be Vietnamese; a successful setLanguage is insufficient", "vi", tts.voice?.locale?.language)
            assertTrue("No Vietnamese voice available", vietnamese.isNotEmpty())
            val finished = CountDownLatch(1)
            val error = AtomicInteger(Int.MIN_VALUE)
            val file = File(context.cacheDir, "synthetic-vietnamese-tts.wav")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { error.set(0); finished.countDown() }
                override fun onError(id: String?) { error.set(-1); finished.countDown() }
                override fun onError(id: String?, code: Int) { error.set(code); finished.countDown() }
            })
            val queued = tts.synthesizeToFile("Xin chào. Tôi đang kiểm tra giọng đọc tiếng Việt.", null, file, "synthetic-vietnamese")
            assertEquals(TextToSpeech.SUCCESS, queued)
            val completed = finished.await(20, TimeUnit.SECONDS)
            println("SPEECH_PROBE synthCompleted=$completed error=${error.get()} wavBytes=${file.length()}")
            assertTrue("Vietnamese synthesis did not finish", completed)
            assertEquals("Vietnamese synthesis failed", 0, error.get())
            assertTrue("Vietnamese synthesis produced no audio", file.length() > 1000)
            file.delete()
        } finally { instrumentation.runOnMainSync { engine?.shutdown() } }
    }
}
