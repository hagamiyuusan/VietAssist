package com.vietassist.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceListeningPolicyTest {
    @Test fun automaticMicrophoneRequiresBothOptInAndPermission() {
        for (enabled in listOf(false, true)) {
            for (permission in listOf(false, true)) {
                var microphoneStarts = 0
                if (automaticListeningAllowed(enabled, permission)) microphoneStarts++
                assertEquals("enabled=$enabled permission=$permission",
                    if (enabled && permission) 1 else 0, microphoneStarts)
            }
        }
    }
}
