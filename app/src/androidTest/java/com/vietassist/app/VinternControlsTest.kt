package com.vietassist.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.vietassist.app.ui.theme.VietAssistTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VinternControlsTest {
    @get:Rule val compose = createComposeRule()
    private var quickReads = 0
    private var detailedReads = 0

    private fun show(state: VinternEngine.PreparationState, busy: Boolean = false) {
        compose.setContent {
            VietAssistTheme {
                Surface(color = Color(0xFF071D17)) {
                    Column(Modifier.padding(20.dp)) {
                        if (!busy) VinternPreparationStatus(state)
                        ReadingActions(state == VinternEngine.PreparationState.Ready, busy,
                            { quickReads++ }, { detailedReads++ })
                    }
                }
            }
        }
    }

    private fun saveSyntheticUi(name: String) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name)
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun preparingKeepsQuickOcrAndBlocksAi() {
        show(VinternEngine.PreparationState.Preparing)
        compose.onNodeWithText("Đang chuẩn bị AI…").assertExists()
        compose.onNodeWithText("Đọc chữ nhanh").assertIsEnabled().performClick()
        compose.onNodeWithText("Đọc chi tiết AI").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, quickReads)
        assertEquals(0, detailedReads)
        saveSyntheticUi("vintern-preparing-ui.png")
    }

    @Test fun preparedAiEnablesDetailedReading() {
        show(VinternEngine.PreparationState.Ready)
        compose.onNodeWithText("AI đã sẵn sàng").assertExists()
        compose.onNodeWithText("Đọc chi tiết AI").assertIsEnabled().performClick()
        assertEquals(1, detailedReads)
        saveSyntheticUi("vintern-ready-ui.png")
    }

    @Test fun failedAiKeepsQuickOcrAvailable() {
        show(VinternEngine.PreparationState.Failed("Lỗi chuẩn bị tổng hợp"))
        compose.onNodeWithText("AI chưa sẵn sàng").assertExists()
        compose.onNodeWithText("Đọc chữ nhanh").assertIsEnabled().performClick()
        compose.onNodeWithText("Đọc chi tiết AI").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, quickReads)
        assertEquals(0, detailedReads)
        saveSyntheticUi("vintern-failed-ui.png")
    }

    @Test fun inferenceInProgressBlocksDuplicateActions() {
        show(VinternEngine.PreparationState.Ready, busy = true)
        compose.onNodeWithText("Đọc chữ nhanh").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Đọc chi tiết AI").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, quickReads)
        assertEquals(0, detailedReads)
    }
}
