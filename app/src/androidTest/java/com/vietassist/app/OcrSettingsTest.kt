package com.vietassist.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OcrSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultsToMlKitAndPersistsExplicitChoice() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ocr-setting-test-${System.nanoTime()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(unused: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        try {
            val settings = OcrSettings(context)
            assertEquals(OcrBackend.ML_KIT, settings.backend.value)
            compose.setContent {
                val backend by settings.backend.collectAsState()
                Surface { OcrSettingsControl(backend, settings::select) }
            }
            compose.onNodeWithText("ML Kit").assertIsSelected()
            compose.onNodeWithText("Vintern").performClick().assertIsSelected()
            assertEquals(OcrBackend.VINTERN, OcrSettings(context).backend.value)
            compose.onNodeWithText("LightOnOCR 0.8B").performClick().assertIsSelected()
            assertEquals(OcrBackend.LIGHTON, OcrSettings(context).backend.value)
            compose.onNodeWithText("ML Kit").performClick().assertIsSelected()
            assertEquals(OcrBackend.ML_KIT, OcrSettings(context).backend.value)
        } finally { app.deleteSharedPreferences(name) }
    }
    @Test fun mlKitReadsSyntheticTextAndReleasesImage() = runBlocking {
        val image = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE)
        Canvas(image).drawText("12345", 100f, 280f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 180f; typeface = Typeface.MONOSPACE
        })
        val result = MlKitOcr.read(image)
        assertTrue(result is VinternEngine.Result.Success)
        assertEquals("12345", (result as VinternEngine.Result.Success).text.filter(Char::isDigit))
        assertTrue(image.isRecycled)
    }
    @Test fun cancelledOcrCannotPublishAndEventuallyReleasesItsOwnImage() = runBlocking {
        val image = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { MlKitOcr.read(image); published = true }
        job.cancelAndJoin()
        withTimeout(15000) { while (!image.isRecycled) delay(20) }
        assertFalse(published)
    }

}
