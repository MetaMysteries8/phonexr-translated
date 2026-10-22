package com.samrat.cardboardhands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerConnectionGuardTest {
    @Test
    fun removalRequiresCachedInputReset() {
        val guard = ControllerConnectionGuard(setOf(10, 20))

        assertTrue(guard.remove(10))
        assertFalse(guard.remove(10))
    }

    @Test
    fun addingControllerDoesNotReleaseActiveInput() {
        val guard = ControllerConnectionGuard(setOf(10))

        guard.add(20)
        assertFalse(guard.update(setOf(10, 20)))
    }

    @Test
    fun replacingControllerStillReleasesRemovedDevices() {
        val guard = ControllerConnectionGuard(setOf(10))

        assertTrue(guard.update(setOf(20)))
    }
}
