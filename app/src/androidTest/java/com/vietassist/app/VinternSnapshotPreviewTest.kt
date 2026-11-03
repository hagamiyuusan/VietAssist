package com.vietassist.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VinternSnapshotPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun processingShowsCapturedImageAndBusyState() {
        compose.setContent {
            VinternSnapshotPreview(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImageBitmap(), true) { }
        }
        compose.onNodeWithContentDescription("Ảnh đã chụp để phân tích").assertExists()
        compose.onNodeWithText("Đang phân tích ảnh đã chụp").assertExists()
        compose.onNodeWithText("Trở lại camera").assertIsNotEnabled()
        capture("vintern-test-processing.png")
    }

    @Test fun completedImageStaysVisibleUntilUserReturnsToCamera() {
        var returned = 0
        compose.setContent {
            VinternSnapshotPreview(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImageBitmap(), false) { returned++ }
        }
        compose.onNodeWithText("Kết quả thuộc ảnh đã chụp").assertExists()
        capture("vintern-test-complete.png")
        compose.onNodeWithText("Trở lại camera").performClick()
        assertEquals(1, returned)
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        cache.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
