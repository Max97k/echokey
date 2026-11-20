package com.akeyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsImeStatusTest {

    @Test
    fun `parseDefaultImePackage extracts package from short ComponentName format`() {
        assertEquals(
            "com.akeyboard",
            SettingsViewModel.parseDefaultImePackage("com.akeyboard/.ime.AKeyboardImeService")
        )
        assertEquals(
            "com.akeyboard",
            SettingsViewModel.parseDefaultImePackage("  com.akeyboard/.ime.AKeyboardImeService  ")
        )
    }

    @Test
    fun `parseDefaultImePackage extracts package from full ComponentName format`() {
        assertEquals(
            "com.akeyboard",
            SettingsViewModel.parseDefaultImePackage("com.akeyboard/com.akeyboard.ime.AKeyboardImeService")
        )
        assertEquals(
            "com.max97k.akeyboard",
            SettingsViewModel.parseDefaultImePackage("com.max97k.akeyboard/com.akeyboard.ime.AKeyboardImeService")
        )
        assertEquals(
            "com.google.android.inputmethod.latin",
            SettingsViewModel.parseDefaultImePackage(
                "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
            )
        )
    }

    @Test
    fun `parseDefaultImePackage returns null for null, blank, and malformed values`() {
        assertNull(SettingsViewModel.parseDefaultImePackage(null))
        assertNull(SettingsViewModel.parseDefaultImePackage(""))
        assertNull(SettingsViewModel.parseDefaultImePackage("   "))
        assertNull(SettingsViewModel.parseDefaultImePackage("com.akeyboard"))
        assertNull(SettingsViewModel.parseDefaultImePackage("com.akeyboard/"))
        assertNull(SettingsViewModel.parseDefaultImePackage("/.ime.AKeyboardImeService"))
    }
}
