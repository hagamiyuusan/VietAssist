package com.vietassist.app

import org.junit.Assert.*
import org.junit.Test

class OcrRoutingTest {
    @Test fun readingHonorsOcrSettingAndSceneDescriptionAlwaysUsesVintern() {
        assertEquals(VisionModel.LIGHTON, localModelFor(VinternEngine.Mode.READ, OcrBackend.LIGHTON))
        for (backend in OcrBackend.entries) assertEquals(VisionModel.VINTERN, localModelFor(VinternEngine.Mode.DESCRIBE, backend))
        assertNull(localModelFor(VinternEngine.Mode.READ, OcrBackend.ML_KIT))
        assertEquals(VisionModel.VINTERN, localModelFor(VinternEngine.Mode.READ, OcrBackend.VINTERN))
    }
}
