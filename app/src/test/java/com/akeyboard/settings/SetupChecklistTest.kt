package com.akeyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecklistTest {

    @Test
    fun `isSetupComplete returns false when any condition is missing`() {
        assertFalse(isSetupComplete(hasKey = false, imeEnabled = false, micGranted = false))
        assertFalse(isSetupComplete(hasKey = false, imeEnabled = true, micGranted = true))
        assertFalse(isSetupComplete(hasKey = true, imeEnabled = false, micGranted = true))
        assertFalse(isSetupComplete(hasKey = true, imeEnabled = true, micGranted = false))
    }

    @Test
    fun `isSetupComplete returns true only when all three conditions are met`() {
        assertTrue(isSetupComplete(hasKey = true, imeEnabled = true, micGranted = true))
    }

    @Test
    fun `AI Studio API key URL matches expected endpoint`() {
        assertEquals("https://aistudio.google.com/apikey", AI_STUDIO_API_KEY_URL)
    }
}
