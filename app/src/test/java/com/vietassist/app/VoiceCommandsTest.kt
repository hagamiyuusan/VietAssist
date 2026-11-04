package com.vietassist.app

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandsTest {
    @Test fun routesVietnameseCommandsAndPrioritizesStopOverRead() {
        assertEquals(VoiceCommand.READ, parseVoiceCommand("ĐỌC VĂN BẢN!"))
        assertEquals(VoiceCommand.READ, parseVoiceCommand("doc van ban"))
        assertEquals(VoiceCommand.READ, parseVoiceCommand("đọc chữ"))
        assertEquals(VoiceCommand.STOP_READING, parseVoiceCommand("dừng đọc văn bản"))
        assertEquals(VoiceCommand.REPEAT, parseVoiceCommand("hãy đọc lại"))
        assertEquals(VoiceCommand.RECAPTURE, parseVoiceCommand("hãy đọc phần khác"))
        assertEquals(VoiceCommand.DESCRIBE, parseVoiceCommand("chụp"))
        assertEquals(VoiceCommand.DESCRIBE, parseVoiceCommand("mô tả xung quanh"))
        assertNull(parseVoiceCommand("xin chào bạn khỏe không"))
        assertNull(parseVoiceCommand(""))
    }
    @Test fun partialCommandsRequireACompleteAlias() {
        assertEquals(VoiceCommand.READ, parseExactVoiceCommand("Đọc văn bản!"))
        assertEquals(VoiceCommand.STOP_READING, parseExactVoiceCommand("dừng đọc văn bản"))
        assertNull(parseExactVoiceCommand("đọc văn"))
        assertNull(parseExactVoiceCommand("tôi muốn đọc văn bản"))
        assertNull(parseExactVoiceCommand("bạn nói đọc văn bản để đọc chữ"))
    }
    @Test fun intentionalReadCommandCanInterruptObjectsButNotEchoAppInstructions() {
        assertTrue(acceptVoiceResult("đọc văn bản", true, "Phía trước: một chiếc ghế."))
        assertFalse(acceptVoiceResult("đọc văn bản", true, "Bạn hãy nói đọc văn bản để đọc chữ."))
        assertFalse(acceptVoiceResult("Xin chào", true, "Xin chào"))
        assertFalse(acceptVoiceResult("tôi muốn đọc văn bản", true, "Phía trước có ghế"))
        assertTrue(acceptVoiceResult("tôi muốn đọc văn bản", false, ""))
        assertFalse(acceptVoiceResult(" ", false, ""))
    }
    @Test fun vietnameseTtsRequiresSupportedStatusAndAnInstalledVietnameseVoice() {
        assertTrue(vietnameseVoiceReady(0, "vi", false))
        assertTrue(vietnameseVoiceReady(2, "vi", false))
        assertFalse(vietnameseVoiceReady(-1, "vi", false))
        assertFalse(vietnameseVoiceReady(-2, "vi", false))
        assertFalse(vietnameseVoiceReady(0, "en", false))
        assertFalse(vietnameseVoiceReady(0, "zh", false))
        assertFalse(vietnameseVoiceReady(0, null, false))
        assertFalse(vietnameseVoiceReady(0, "vi", true))
    }
}
