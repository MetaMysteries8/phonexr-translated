package com.samrat.cardboardhands

/**
 * Tracks controller membership independently of Android so disconnect behavior can be unit tested.
 * A removed or reclassified device requires cached inputs to be released; additions do not.
 */
internal class ControllerConnectionGuard(initialIds: Set<Int> = emptySet()) {
    private var controllerIds = initialIds.toSet()

    fun add(deviceId: Int) {
        controllerIds = controllerIds + deviceId
    }

    /** Returns true only when the removed device was an active controller. */
    fun remove(deviceId: Int): Boolean {
        if (deviceId !in controllerIds) return false
        controllerIds = controllerIds - deviceId
        return true
    }

    fun update(currentIds: Set<Int>): Boolean {
        val controllerRemoved = (controllerIds - currentIds).isNotEmpty()
        controllerIds = currentIds.toSet()
        return controllerRemoved
    }
}
