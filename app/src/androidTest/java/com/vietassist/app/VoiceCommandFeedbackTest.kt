package com.vietassist.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class VoiceCommandFeedbackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText().trim() }

    @Test fun acceptedReadCommandShowsFeedbackAndRunsSelectedOcr() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.getSharedPreferences("lumora_preferences", 0).getBoolean("onboarding_v2_completed", false))
        val previousProvider = shell("settings --user 0 get secure voice_recognition_service")
        require(previousProvider == "null" || previousProvider.matches(Regex("[A-Za-z0-9_./]+")))
        val voices = VoiceSettings.get(context)
        val previousAlways = voices.alwaysListening.value
        val ocr = OcrSettings.get(context)
        val previousOcr = ocr.backend.value
        var activity: ActivityScenario<MainActivity>? = null
        try {
            TestCommandRecognitionService.delivered = false
            shell("settings --user 0 put secure voice_recognition_service com.vietassist.app/com.vietassist.app.TestCommandRecognitionService")
            voices.setAlwaysListening(true)
            ocr.select(OcrBackend.ML_KIT)
            activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(5000) {
                compose.onAllNodes(hasText("Đã nhận lệnh: Đọc văn bản · ML Kit")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(20000) {
                compose.onAllNodes(hasText("ML Kit đọc được:", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(hasText("OCR ML Kit chưa đọc được ảnh.", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            // The fixture sends a late final result eight seconds after its partial.
            // It must not start a second camera capture after the accepted command.
            Thread.sleep(9000)
            org.junit.Assert.assertTrue("Late final result must not capture again",
                compose.onAllNodes(hasText("Đang chụp để đọc chữ", substring = true)).fetchSemanticsNodes().isEmpty())
        } finally {
            activity?.close()
            if (previousProvider == "null") shell("settings --user 0 delete secure voice_recognition_service")
            else shell("settings --user 0 put secure voice_recognition_service $previousProvider")
            voices.setAlwaysListening(previousAlways)
            ocr.select(previousOcr)
            shell("am stopservice -n com.vietassist.app/.LumoraVoiceService")
        }
    }
}
