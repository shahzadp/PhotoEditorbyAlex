package com.alex.photoeditor.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Runs [onHold] when a finger goes down, and again when it lifts.
 *
 * Used for the "compare with original" control, where holding should reveal the
 * untouched image rather than toggling a switch.
 */
fun Modifier.pressAndHold(onDown: () -> Unit, onUp: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onDown()

            var lifted = false
            while (!lifted) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.changedToUpIgnoreConsumed()) {
                    lifted = true
                }
            }
            onUp()
        }
    }
