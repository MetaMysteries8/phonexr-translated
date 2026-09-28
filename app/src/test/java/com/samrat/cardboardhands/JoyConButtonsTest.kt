package com.samrat.cardboardhands

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class JoyConButtonsTest {
    @Test fun standardAndroidRightStickKeepsZRz() {
        val axes = setOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        assertEquals(MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ, JoyConButtons.rightStickAxes { it in axes })
    }

    @Test fun alternateRightStickUsesRxRyWhenStandardAxesAreMissing() {
        val axes = setOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        assertEquals(MotionEvent.AXIS_RX to MotionEvent.AXIS_RY, JoyConButtons.rightStickAxes { it in axes })
    }
}
