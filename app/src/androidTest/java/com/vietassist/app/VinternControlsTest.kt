package com.vietassist.app

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class VinternControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun preparingExplainsThatCameraRemainsAvailable() {
        compose.setContent { Surface(color = Color(0xFF071D17)) { VinternPreparationStatus(VinternEngine.PreparationState.Preparing) } }
        compose.onNodeWithText("Đang chuẩn bị AI…").assertExists()
        compose.onNodeWithText("Camera và khung nhận diện vẫn dùng được trong lúc chờ.").assertExists()
        compose.onNodeWithText("Đọc chữ nhanh").assertDoesNotExist()
        compose.onNodeWithText("Đọc chi tiết AI").assertDoesNotExist()
    }
}
