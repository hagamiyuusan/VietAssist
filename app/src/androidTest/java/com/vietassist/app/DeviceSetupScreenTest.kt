package com.vietassist.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeviceSetupScreenTest {
    @get:Rule val compose = createComposeRule()
    private var downloads = 0
    private var continues = 0

    private fun show(nativePresent: Boolean) {
        compose.setContent {
            DeviceSetupScreen(
                requirements = VinternRequirements(true, nativePresent, 8L shl 30, 3L shl 30, false, 2L shl 30, 1L shl 30),
                cameraGranted = false, micGranted = false, modelReady = false,
                downloading = false, progress = 0, status = "",
                onCamera = {}, onMicrophone = {}, onSettings = {},
                onDownload = { downloads++ }, onRefresh = {}, onReadStatus = {},
                onContinue = { continues++ }
            )
        }
    }

    @Test fun unsupportedDeviceBlocksDownloadButKeepsBasicUse() {
        show(nativePresent = false)
        compose.onNodeWithText("Tải mô hình AI").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Tiếp tục với nhận diện và đọc nhanh").performScrollTo().performClick()
        assertEquals(0, downloads)
        assertEquals(1, continues)
    }

    @Test fun supportedDeviceCanStartDownloadWithoutMicrophonePermission() {
        show(nativePresent = true)
        compose.onNodeWithText("Tải mô hình AI").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, downloads)
    }
}
