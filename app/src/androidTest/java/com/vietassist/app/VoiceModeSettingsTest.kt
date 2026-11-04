package com.vietassist.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vietassist.app.ui.theme.VietAssistTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VoiceModeSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modeDefaultsToPushToTalkAndPersistsBothChoices() {
        // Dùng vùng lưu riêng, không thay đổi lựa chọn thật của người dùng.
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "voice-mode-test-${System.nanoTime()}"
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(unused: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        try {
            val settings = VoiceSettings(isolated)
            assertFalse(settings.alwaysListening.value)
            compose.setContent {
                val always by settings.alwaysListening.collectAsState()
                VietAssistTheme { Surface { VoiceModeSettings(always, settings::setAlwaysListening) } }
            }
            compose.onNodeWithText("Nhấn để nói").assertIsSelected()
            compose.onNodeWithText("Luôn nghe").performClick()
            compose.onNodeWithText("Luôn nghe").assertIsSelected()
            assertTrue(VoiceSettings(isolated).alwaysListening.value)
            compose.onNodeWithText("Nhấn để nói").performClick()
            compose.onNodeWithText("Nhấn để nói").assertIsSelected()
            compose.onNodeWithText("Luôn nghe").assertIsNotSelected()
            assertFalse(VoiceSettings(isolated).alwaysListening.value)
        } finally {
            app.deleteSharedPreferences(name)
        }
    }
}
