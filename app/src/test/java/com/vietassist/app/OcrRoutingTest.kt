package com.vietassist.app

import org.junit.Assert.*
import org.junit.Test

class OcrRoutingTest {
    @Test fun readingHonorsSettingAndSceneDescriptionDoesNotUseAVlm() {
        assertEquals(VisionModel.LIGHTON, localModelFor(LightOnEngine.Mode.READ, OcrBackend.LIGHTON))
        assertNull(localModelFor(LightOnEngine.Mode.READ, OcrBackend.ML_KIT))
        for (backend in OcrBackend.entries) assertNull(localModelFor(LightOnEngine.Mode.DESCRIBE, backend))
    }
}
