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

    @Test
    fun chunkTextForCommitPreservesMultilineAndSurrogatePairs() {
        assertEquals(emptyList<String>(), AKeyboardImeService.chunkTextForCommit(""))
        assertEquals(
            listOf("line1\nline2"),
            AKeyboardImeService.chunkTextForCommit("line1\nline2")
        )

        val surrogateBoundaryText = "ab\uD83D\uDE00cd"
        val chunks = AKeyboardImeService.chunkTextForCommit(surrogateBoundaryText, maxChunkChars = 3)
        assertEquals(listOf("ab", "\uD83D\uDE00c", "d"), chunks)
        assertEquals(surrogateBoundaryText, chunks.joinToString(""))
        assertEquals(false, Character.isHighSurrogate(chunks[0].last()))
        assertEquals(false, Character.isLowSurrogate(chunks[1].first()))
    }
}
