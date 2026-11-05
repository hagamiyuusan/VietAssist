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
    private var lightOnDownloads = 0

    private fun show(nativePresent: Boolean, backend: OcrBackend = OcrBackend.LIGHTON, gpuPresent: Boolean = true) {
        compose.setContent {
            DeviceSetupScreen(
                requirements = LightOnRequirements(true, nativePresent, 8L shl 30, 3L shl 30, false, 2L shl 30, 1L shl 30, gpuAvailable = gpuPresent),
                cameraGranted = false, micGranted = false, status = "",
                onCamera = {}, onMicrophone = {}, onSettings = {},
                onRefresh = {}, onReadStatus = {},
                onContinue = { continues++ },
                ocrBackend = backend, onDownloadLightOn = { lightOnDownloads++ }
            )
        }
    }

    @Test fun unsupportedDeviceBlocksDownloadButKeepsBasicUse() {
        show(nativePresent = false)
        compose.onNodeWithText("Tải LightOnOCR").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Tiếp tục với nhận diện vật thể").performScrollTo().performClick()
        assertEquals(0, downloads)
        assertEquals(1, continues)
    }

    @Test fun supportedDeviceCanStartDownloadWithoutMicrophonePermission() {
        show(nativePresent = true)
        compose.onNodeWithText("Tải LightOnOCR").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, lightOnDownloads)
    }
    @Test fun lightOnDownloadUsesItsOwnAction() {
        show(nativePresent = true, backend = OcrBackend.LIGHTON)
        compose.onNodeWithText("Tải LightOnOCR").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, lightOnDownloads)
        assertEquals(0, downloads)
    }

    @Test fun unsupportedDeviceAlsoBlocksLightOnDownload() {
        show(nativePresent = false, backend = OcrBackend.LIGHTON)
        compose.onNodeWithText("Tải LightOnOCR").performScrollTo().assertIsNotEnabled()
        assertEquals(0, lightOnDownloads)
    }

    @Test fun missingGpuBlocksLightOnDespiteNativeLibrary() {
        show(nativePresent = true, gpuPresent = false)
        compose.onNodeWithText("Tải LightOnOCR").performScrollTo().assertIsNotEnabled()
        assertEquals(0, lightOnDownloads)
        compose.onNodeWithText("Tiếp tục với nhận diện vật thể").performScrollTo().performClick()
        assertEquals(1, continues)
    }

}
