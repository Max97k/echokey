package com.akeyboard.ime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeActionResolverTest {

    @Test
    fun `resolveEffectiveImeAction returns IME_ACTION_SEND when no-enter-action flag is absent`() {
        val action = AKeyboardImeService.resolveEffectiveImeAction(EditorInfo.IME_ACTION_SEND)
        assertEquals(EditorInfo.IME_ACTION_SEND, action)
    }

    @Test
    fun `resolveEffectiveImeAction returns IME_ACTION_NONE when IME_FLAG_NO_ENTER_ACTION is set`() {
        val imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val action = AKeyboardImeService.resolveEffectiveImeAction(imeOptions)
        assertEquals(EditorInfo.IME_ACTION_NONE, action)
    }

    @Test
    fun `resolveEffectiveImeAction preserves masked action without flag and suppresses other actions with flag`() {
        assertEquals(
            EditorInfo.IME_ACTION_SEARCH,
            AKeyboardImeService.resolveEffectiveImeAction(
                EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            )
        )
        assertEquals(
            EditorInfo.IME_ACTION_NONE,
            AKeyboardImeService.resolveEffectiveImeAction(
                EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            )
        )
    }
}
