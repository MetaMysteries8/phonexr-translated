package com.samrat.cardboardhands

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Key and stick events go to the focused game, never to a background service.
 * An accessibility filter is the only non-root way to read a Joy-Con while a game is in front.
 */
class JoyConInputService : AccessibilityService() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchReceiver: android.content.BroadcastReceiver? = null
    private var watching = false
    private var passthroughReceiver: android.content.BroadcastReceiver? = null
    /** Flags as configured, restored when the cinema stops passing Joy-Con input to its game. */
    private var normalFlags = 0
    private var inputManager: InputManager? = null
    private val connectionGuard = ControllerConnectionGuard()
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            if (JoyConButtons.isController(InputDevice.getDevice(deviceId))) connectionGuard.add(deviceId)
        }
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (connectionGuard.remove(deviceId)) JoyConButtons.clear()
        }
        override fun onInputDeviceChanged(deviceId: Int) = refreshControllerConnections()
    }
    private val publisher = object : Runnable {
        override fun run() {
            if (!watching) return
            JoyConBridge.publish(this@JoyConInputService)
            handler.postDelayed(this, 60L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Sticks arrive only when the service asks for motion events; there is no manifest attribute.
        if (sticksSupported) {
            serviceInfo = serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
                motionEventSources = android.view.InputDevice.SOURCE_JOYSTICK or android.view.InputDevice.SOURCE_GAMEPAD
            }
        }
        JoyConButtons.apply(Settings.load(this))
        inputManager = getSystemService(InputManager::class.java).also { manager ->
            connectionGuard.update(controllerIds())
            manager.registerInputDeviceListener(inputDeviceListener, handler)
        }
        normalFlags = serviceInfo.flags
        passthroughReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val enabled = intent.getBooleanExtra("enabled", false)
                // Without the filter flags, Joy-Con keys and sticks reach the focused app as a normal gamepad.
                serviceInfo = serviceInfo.apply {
                    flags = if (enabled) normalFlags and
                        (android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                            android.accessibilityservice.AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS).inv()
                    else normalFlags
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, passthroughReceiver, android.content.IntentFilter(CinemaActivity.ACTION_JOYCON_PASSTHROUGH),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        watchReceiver = JoyConBridge.watchRequests(this) { watch, learning ->
            watching = watch
            JoyConButtons.learn(if (watch && learning) ::onLearned else null)
            handler.removeCallbacks(publisher)
            if (watch) handler.post(publisher)
        }
    }

    private fun onLearned(keyCode: Int, left: Boolean) {
        handler.post { JoyConBridge.publish(this, learnedKey = keyCode, learnedLeft = left) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = JoyConButtons.onKey(event)

    override fun onMotionEvent(event: MotionEvent) {
        JoyConButtons.onMotion(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = JoyConButtons.clear()

    override fun onUnbind(intent: Intent?): Boolean {
        watching = false
        handler.removeCallbacks(publisher)
        watchReceiver?.let { unregisterReceiver(it) }
        passthroughReceiver?.let { unregisterReceiver(it) }
        passthroughReceiver = null
        watchReceiver = null
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        inputManager = null
        JoyConButtons.learn(null)
        JoyConButtons.clear()
        return super.onUnbind(intent)
    }

    /**
     * Android does not guarantee key-up or centered-axis events when Bluetooth/USB disappears.
     * Drop every cached button and stick value as soon as a controller leaves the active set.
     */
    private fun refreshControllerConnections() {
        if (connectionGuard.update(controllerIds())) JoyConButtons.clear()
    }

    private fun controllerIds(): Set<Int> = InputDevice.getDeviceIds()
        .filterTo(mutableSetOf()) { id -> JoyConButtons.isController(InputDevice.getDevice(id)) }

    companion object {
        /** Sticks need onMotionEvent, added in Android 14. */
        val sticksSupported = Build.VERSION.SDK_INT >= 34

        fun isEnabled(context: Context): Boolean {
            val enabled = AndroidSettings.Secure.getString(
                context.contentResolver,
                AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                it.equals("${context.packageName}/${JoyConInputService::class.java.name}", true) ||
                    it.equals("${context.packageName}/.JoyConInputService", true)
            }
        }
    }
}
