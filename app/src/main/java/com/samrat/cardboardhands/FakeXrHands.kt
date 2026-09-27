package com.samrat.cardboardhands

/** Virtual hand positions for a conventional two-stick gamepad. No pose is inferred from buttons. */
object FakeXrHands {
    data class Pose(val x: Float, val y: Float, val z: Float)
    data class Result(val left: Pose, val right: Pose, val forwardSticks: Boolean)

    /** Marker 0 may be attached to the center of the gamepad in the Full edition. */
    fun map(
        mode: Settings.FakeXrMode,
        leftStick: FloatArray,
        rightStick: FloatArray,
        marker: MarkerPose? = null
    ): Result {
        val centerX = marker?.x ?: .5f
        val centerY = marker?.y ?: .55f
        val centerZ = marker?.distance?.let { ((it - .35f) / .45f).coerceIn(0f, 1f) } ?: .5f
        fun hand(side: Float, stick: FloatArray): Pose {
            val moving = mode == Settings.FakeXrMode.STICK_HANDS
            return Pose(
                (centerX + side * .09f + if (moving) stick[0] * .20f else 0f).coerceIn(0f, 1f),
                (centerY + (if (moving) -stick[1] * .20f else 0f)).coerceIn(0f, 1f),
                centerZ
            )
        }
        return Result(hand(-1f, leftStick), hand(1f, rightStick), mode == Settings.FakeXrMode.LOCOMOTION)
    }
}
