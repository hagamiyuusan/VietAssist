package com.vietassist.app

import org.junit.Assert.*
import org.junit.Test

class OcrRoutingTest {
    @Test fun defaultInstructionsStayBoundToModelAndTask() {
        for (mode in VinternEngine.Mode.entries) {
            assertEquals("", VinternEngine.defaultPrompt(VisionModel.LIGHTON, mode))
            assertEquals(mode.question, VinternEngine.defaultPrompt(VisionModel.VINTERN, mode))
        }
        assertNotEquals(VinternEngine.Mode.READ.question, VinternEngine.Mode.DESCRIBE.question)
    }

    @Test fun readingHonorsOcrSettingAndSceneDescriptionAlwaysUsesVintern() {
        assertEquals(VisionModel.LIGHTON, localModelFor(VinternEngine.Mode.READ, OcrBackend.LIGHTON))
        for (backend in OcrBackend.entries) assertEquals(VisionModel.VINTERN, localModelFor(VinternEngine.Mode.DESCRIBE, backend))
        assertNull(localModelFor(VinternEngine.Mode.READ, OcrBackend.ML_KIT))
    }
}
