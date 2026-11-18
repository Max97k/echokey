package com.akeyboard.ime

class TouchpadCursorController(
    var stepXPx: Float,
    var stepYPx: Float,
    var multiline: Boolean,
    private val onKey: (keyCode: Int) -> Unit,
    private val onHaptic: () -> Unit,
    private val maxKeysPerDelta: Int = DEFAULT_MAX_KEYS_PER_DELTA
) {
    private var accX = 0f
    private var accY = 0f

    fun reset() {
        accX = 0f
        accY = 0f
    }

    fun onDelta(dx: Float, dy: Float) {
        if (stepXPx <= 0f || stepYPx <= 0f) return

        // Reset accumulator on axis direction reversal for crisp responsiveness
        if ((accX > 0f && dx < 0f) || (accX < 0f && dx > 0f)) accX = 0f
        accX += dx

        var emitted = 0
        while (accX >= stepXPx && emitted < maxKeysPerDelta) {
            onKey(KEYCODE_DPAD_RIGHT)
            onHaptic()
            accX -= stepXPx
            emitted++
        }
        while (accX <= -stepXPx && emitted < maxKeysPerDelta) {
            onKey(KEYCODE_DPAD_LEFT)
            onHaptic()
            accX += stepXPx
            emitted++
        }
        if (emitted >= maxKeysPerDelta) accX = 0f

        if (!multiline) {
            accY = 0f
            return
        }

        if ((accY > 0f && dy < 0f) || (accY < 0f && dy > 0f)) accY = 0f
        accY += dy

        var emittedY = 0
        while (accY >= stepYPx && emittedY < maxKeysPerDelta) {
            onKey(KEYCODE_DPAD_DOWN)
            onHaptic()
            accY -= stepYPx
            emittedY++
        }
        while (accY <= -stepYPx && emittedY < maxKeysPerDelta) {
            onKey(KEYCODE_DPAD_UP)
            onHaptic()
            accY += stepYPx
            emittedY++
        }
        if (emittedY >= maxKeysPerDelta) accY = 0f
    }

    companion object {
        // Android KeyEvent.KEYCODE_DPAD_* constants kept pure Kotlin for unit testing
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val DEFAULT_MAX_KEYS_PER_DELTA = 8
    }
}
