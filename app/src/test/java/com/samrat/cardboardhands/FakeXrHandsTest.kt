package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeXrHandsTest {
    @Test fun sticksMoveHandsButAreNotForwarded() {
        val pose = FakeXrHands.map(
            Settings.FakeXrMode.STICK_HANDS,
            floatArrayOf(-1f, 1f), floatArrayOf(1f, -1f)
        )
        assertEquals(.21f, pose.left.x, .0001f)
        assertEquals(.35f, pose.left.y, .0001f)
        assertEquals(.79f, pose.right.x, .0001f)
        assertEquals(.75f, pose.right.y, .0001f)
        assertFalse(pose.forwardSticks)
    }

    @Test fun locomotionKeepsHandsNearTrackedMarker() {
        val marker = MarkerPose(found = true, x = .7f, y = .3f, distance = .8f)
        val pose = FakeXrHands.map(
            Settings.FakeXrMode.LOCOMOTION,
            floatArrayOf(-1f, 1f), floatArrayOf(1f, -1f), marker
        )
        assertEquals(.61f, pose.left.x, .0001f)
        assertEquals(.79f, pose.right.x, .0001f)
        assertEquals(.3f, pose.left.y, .0001f)
        assertEquals(1f, pose.left.z, .0001f)
        assertTrue(pose.forwardSticks)
    }
}
