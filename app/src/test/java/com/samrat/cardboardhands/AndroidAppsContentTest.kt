package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAppsContentTest {
    @Test fun virtualScreenExcludesRecognizedXrAndLatitude() {
        val result = AndroidAppsContent.flatPackages(
            launchers = listOf("flat.game", "xr.game", "flat.game", "latitude"),
            xrPackages = setOf("xr.game"),
            ownPackage = "latitude"
        )

        assertEquals(listOf("flat.game"), result)
    }

    @Test fun ordinaryLaunchersRemainAvailable() {
        val result = AndroidAppsContent.flatPackages(
            launchers = listOf("game.one", "game.two"),
            xrPackages = emptySet(),
            ownPackage = "latitude"
        )

        assertEquals(listOf("game.one", "game.two"), result)
    }
}
