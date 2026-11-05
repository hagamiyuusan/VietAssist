package com.vietassist.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        val args = InstrumentationRegistry.getArguments()
        val backend = args.getString("ocr_backend")?.let(OcrBackend::valueOf) ?: OcrBackend.ML_KIT
        val sceneFirst = args.getString("start_scene_first").toBoolean()
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
            if (!sceneFirst) shell("settings --user 0 put secure voice_recognition_service com.vietassist.app/com.vietassist.app.TestCommandRecognitionService")
            voices.setAlwaysListening(!sceneFirst)
            ocr.select(backend)
            activity = ActivityScenario.launch(MainActivity::class.java)
            if (sceneFirst) {
                compose.waitUntil(15000) {
                    compose.onAllNodes(hasText("CHỤP · MÔ TẢ XUNG QUANH")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("CHỤP · MÔ TẢ XUNG QUANH").performClick()
                compose.waitUntil(10000) {
                    compose.onAllNodes(hasText("Đang phân tích ảnh đã chụp...", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                shell("settings --user 0 put secure voice_recognition_service com.vietassist.app/com.vietassist.app.TestCommandRecognitionService")
                compose.onNodeWithText("Nhấn để nói").performClick()
            }
            compose.waitUntil(5000) {
                compose.onAllNodes(hasText("Đã nhận lệnh: Đọc văn bản · ${backend.label}")).fetchSemanticsNodes().isNotEmpty()
            }
            if (sceneFirst) {
                compose.waitUntil(4000) {
                    compose.onAllNodes(hasText("Đang chụp để đọc chữ bằng ${backend.label}", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                org.junit.Assert.assertTrue("A completed manual session must release its microphone control",
                    compose.onAllNodes(hasText("Nhấn để nói")).fetchSemanticsNodes().isNotEmpty())
            }
            compose.waitUntil(if (backend == OcrBackend.LIGHTON) 120000 else 20000) {
                compose.onAllNodes(hasText("${backend.label} đọc được:", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                    (backend == OcrBackend.ML_KIT && compose.onAllNodes(hasText("OCR ML Kit chưa đọc được ảnh.", substring = true)).fetchSemanticsNodes().isNotEmpty())
            }
            org.junit.Assert.assertTrue("The new capture must own the result panel",
                compose.onAllNodes(hasText("KẾT QUẢ TỪ ẢNH VỪA CHỤP")).fetchSemanticsNodes().isNotEmpty())
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
