package com.samrat.cardboardhands

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "VrHomeActivity.kt",
        )
        val translatedLabels = criticalFiles.flatMap { name ->
            Regex("tr\\(\"([^\"]+)\"\\)")
                .findAll(File(sourceDir, name).readText())
                .map { it.groupValues[1] }
                .toList()
        }.toSet()

        val missing = translatedLabels.filterNot(englishKeys::contains).sorted()
        assertTrue("Missing English translations: $missing", missing.isEmpty())
    }

    @Test
    fun settingsAndVrHomeDoNotDrawHardcodedCyrillic() {
        val sourceDir = File(repoRoot, "app/src/main/java/com/samrat/cardboardhands")
        val criticalFiles = listOf(
            "SettingsActivity.kt",
            "SettingsContent.kt",
            "HandTrackingService.kt",
            "VrHomeActivity.kt",
        )
        val cyrillicString = Regex("\"(?:\\\\.|[^\"\\\\])*[А-Яа-яЁё](?:\\\\.|[^\"\\\\])*\"")
        val translatedPrefix = Regex("""tr\s*\(\s*$""")
        val hardcoded = criticalFiles.flatMap { name ->
            val source = File(sourceDir, name).readText()
            cyrillicString.findAll(source).mapNotNull { match ->
                val prefix = source.substring(maxOf(0, match.range.first - 16), match.range.first)
                if (translatedPrefix.containsMatchIn(prefix)) null
                else {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    "$name:$line ${match.value}"
                }
            }.toList()
        }

        assertTrue("Hard-coded Cyrillic UI literals: $hardcoded", hardcoded.isEmpty())
    }

    @Test
    fun defaultAndroidServiceStringsAreEnglish() {
        val defaultStrings = File(repoRoot, "app/src/main/res/values/strings.xml").readText()
        assertTrue(
            "Default Android resources must not fall back to Russian",
            !Regex("[А-Яа-яЁё]").containsMatchIn(defaultStrings),
        )
    }
}
