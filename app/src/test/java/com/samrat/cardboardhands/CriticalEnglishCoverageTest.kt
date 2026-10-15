package com.samrat.cardboardhands

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CriticalEnglishCoverageTest {
    private val repoRoot: File by lazy {
        generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    @Test
    fun settingsNotificationAndKeyboardHaveEnglishForEveryTranslatedLabel() {
        val sourceDir = File(repoRoot, "app/src/main/java/com/samrat/cardboardhands")
        val l10n = File(sourceDir, "L10n.kt").readText()
        val englishKeys = Regex("\"([^\"]+)\"\\s+to\\s+")
            .findAll(l10n)
            .map { it.groupValues[1] }
            .toSet()
        val criticalFiles = listOf(
            "SettingsActivity.kt",
            "SettingsContent.kt",
            "HandTrackingService.kt",
            "KeyboardPanel.kt",
        )
        val translatedLabels = criticalFiles.flatMap { name ->
            Regex("tr\\(\"([^\"]+)\"\\)")
                .findAll(File(sourceDir, name).readText())
                .map { it.groupValues[1] }
                .toList()
        }.toSet()

        val missing = translatedLabels.filterNot(englishKeys::contains).sorted()
        assertTrue(missing.isEmpty(), "Missing English translations: $missing")
    }

    @Test
    fun defaultAndroidServiceStringsAreEnglish() {
        val defaultStrings = File(repoRoot, "app/src/main/res/values/strings.xml").readText()
        assertTrue(!Regex("[А-Яа-яЁё]").containsMatchIn(defaultStrings),
            "Default Android resources must not fall back to Russian")
    }
}
