package com.vietassist.app

import org.junit.Assert.*
import org.junit.Test

class VinternRequirementsTest {
    private val good = VinternRequirements(
        is64Bit = true, nativeLibraryPresent = true,
        totalRam = 8L * 1024 * 1024 * 1024, availableRam = 3L * 1024 * 1024 * 1024,
        lowMemory = false, freeStorage = 1024L, requiredStorage = 1024L
    )

    @Test fun exactStorageBoundaryAllowsDownload() {
        assertNull(good.downloadBlocker)
        assertNull(good.inferenceBlocker)
    }

    @Test fun missingOpenClBlocksVintern() {
        assertNotNull(good.copy(openClAvailable = false).inferenceBlocker)
        assertNotNull(good.copy(openClAvailable = false).downloadBlocker)
        assertNull(good.copy(openClAvailable = true).inferenceBlocker)
    }

    @Test fun nativeAndroidVersionBoundaryIs28() {
        assertNotNull(good.copy(androidSdk = 27).inferenceBlocker)
        assertNull(good.copy(androidSdk = 28).inferenceBlocker)
    }

    @Test fun oneByteShortBlocksOnlyDownload() {
        val device = good.copy(freeStorage = 1023)
        assertNotNull(device.downloadBlocker)
        assertNull(device.inferenceBlocker)
    }

    @Test fun unsupportedRuntimeBlocksDownloadAndInference() {
        val device = good.copy(is64Bit = false)
        assertNotNull(device.downloadBlocker)
        assertNotNull(device.inferenceBlocker)
    }

    @Test fun missingNativeLibraryBlocksDownloadAndInference() {
        val device = good.copy(nativeLibraryPresent = false)
        assertNotNull(device.downloadBlocker)
        assertNotNull(device.inferenceBlocker)
    }

    @Test fun lowMemoryBlocksInferenceButAllowsDownload() {
        val device = good.copy(lowMemory = true)
        assertNotNull(device.inferenceBlocker)
        assertNull(device.downloadBlocker)
    }

    @Test fun recommendationsAreNotHardRequirements() {
        val device = good.copy(totalRam = 3L shl 30, availableRam = 1L shl 30)
        assertTrue(device.ramWarning)
        assertNull(device.inferenceBlocker)
        assertNull(device.downloadBlocker)
    }

    @Test fun enoughRecommendedRamDoesNotWarn() {
        assertFalse(good.ramWarning)
        assertFalse(good.copy(totalRam = 4L shl 30, availableRam = 2L shl 30).ramWarning)
        assertTrue(good.copy(totalRam = (4L shl 30) - 1).ramWarning)
        assertTrue(good.copy(availableRam = (2L shl 30) - 1).ramWarning)
    }
}
