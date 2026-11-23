// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadCursorControllerTest {

    @Test
    fun belowThresholdEmitsNothing() {
        val keys = mutableListOf<Int>()
        var haptics = 0
        val controller = TouchpadCursorController(
            stepXPx = 16f,
            stepYPx = 28f,
            multiline = true,
            onKey = { keys += it },
            onHaptic = { haptics++ }
        )
        controller.onDelta(15.9f, -27.9f)
        assertEquals(emptyList<Int>(), keys)
        assertEquals(0, haptics)
    }

    @Test
    fun largeDeltaEmitsMultipleAndCarriesRemainder() {
        val keys = mutableListOf<Int>()
        val controller = TouchpadCursorController(
            stepXPx = 16f,
            stepYPx = 28f,
            multiline = false,
            onKey = { keys += it },
            onHaptic = {}
        )
        controller.onDelta(35f, 0f)
        assertEquals(
            listOf(
                TouchpadCursorController.KEYCODE_DPAD_RIGHT,
                TouchpadCursorController.KEYCODE_DPAD_RIGHT
            ),
            keys
        )
        // Remainder is 3f; adding 13f should reach 16f and emit 1 more
        controller.onDelta(13f, 0f)
        assertEquals(3, keys.size)
    }

    @Test
    fun directionReversalResetsAccumulator() {
        val keys = mutableListOf<Int>()
        val controller = TouchpadCursorController(
            stepXPx = 16f,
            stepYPx = 28f,
            multiline = false,
            onKey = { keys += it },
            onHaptic = {}
        )
        controller.onDelta(10f, 0f)
        // Reverse by -20f: resets +10f to 0, then -20f emits 1 LEFT with -4f remainder
        controller.onDelta(-20f, 0f)
        assertEquals(listOf(TouchpadCursorController.KEYCODE_DPAD_LEFT), keys)
    }

    @Test
    fun singleLineDropsVerticalDelta() {
        val keys = mutableListOf<Int>()
        val controller = TouchpadCursorController(
            stepXPx = 16f,
            stepYPx = 28f,
            multiline = false,
            onKey = { keys += it },
            onHaptic = {}
        )
        controller.onDelta(0f, 100f)
        assertEquals(emptyList<Int>(), keys)
    }

    @Test
    fun multilineEmitsUpAndDown() {
        val keys = mutableListOf<Int>()
        val controller = TouchpadCursorController(
            stepXPx = 16f,
            stepYPx = 28f,
            multiline = true,
            onKey = { keys += it },
            onHaptic = {}
        )
        controller.onDelta(0f, 30f)
        controller.onDelta(0f, -30f)
        assertEquals(
            listOf(
                TouchpadCursorController.KEYCODE_DPAD_DOWN,
                TouchpadCursorController.KEYCODE_DPAD_UP
            ),
            keys
        )
    }
}
