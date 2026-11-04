package com.vietassist.app

import org.junit.Assert.*
import org.junit.Test

class OcrRoutingTest {
    @Test fun readingHonorsOcrSettingAndSceneDescriptionAlwaysUsesVintern() {
        assertFalse(usesVintern(VinternEngine.Mode.READ, OcrBackend.ML_KIT))
        assertTrue(usesVintern(VinternEngine.Mode.READ, OcrBackend.VINTERN))
        assertTrue(usesVintern(VinternEngine.Mode.DESCRIBE, OcrBackend.ML_KIT))
        assertTrue(usesVintern(VinternEngine.Mode.DESCRIBE, OcrBackend.VINTERN))
    }
}
