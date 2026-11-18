package com.akeyboard.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.akeyboard.ime.TouchpadCursorController

@Composable
fun TouchpadSurface(
    stepXDp: Int,
    stepYDp: Int,
    multiline: Boolean,
    enabled: Boolean,
    onDpadKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val stepXPx = with(density) { stepXDp.dp.toPx() }
    val stepYPx = with(density) { stepYDp.dp.toPx() }
    val edgeDeadZonePx = with(density) { 24.dp.toPx() }

    val controller = remember {
        TouchpadCursorController(
            stepXPx = stepXPx,
            stepYPx = stepYPx,
            multiline = multiline,
            onKey = onDpadKey,
            onHaptic = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        )
    }

    LaunchedEffect(stepXPx, stepYPx, multiline) {
        controller.stepXPx = stepXPx
        controller.stepYPx = stepYPx
        controller.multiline = multiline
    }

    Box(
        modifier = modifier
            .systemGestureExclusion()
            .pointerInput(enabled, edgeDeadZonePx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    if (startX < edgeDeadZonePx || startX > size.width - edgeDeadZonePx) {
                        return@awaitEachGesture
                    }
                    controller.reset()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            controller.reset()
                            break
                        }
                        val delta = change.positionChange()
                        if (delta.x != 0f || delta.y != 0f) {
                            controller.onDelta(delta.x, delta.y)
                            change.consume()
                        }
                    }
                }
            },
        content = content
    )
}
