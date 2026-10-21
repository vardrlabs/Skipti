package com.vardrlabs.skipti

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D-212: the twins draw the same colours. iOS's palette rows
 * (`Tokens.palette` in DesignTokens.swift) and its six group tones
 * (GroupsScreen.swift) are parsed and compared, set by set, with Kotlin's
 * `Tok` and `GroupTone`. Both Swift files are declared as test inputs in
 * build.gradle.kts, so a Swift change reruns this test.
 */
class PaletteParityTest {
    private val ios = java.io.File("../../ios/Skipti/App")
    private val sets = ThemeSet.entries // the column order on both shells

    private fun color(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))

    private val column = """\("(#[0-9A-Fa-f]{6})", "(#[0-9A-Fa-f]{6})", "(#[0-9A-Fa-f]{6})", "(#[0-9A-Fa-f]{6})", "(#[0-9A-Fa-f]{6})"\)"""

    private fun columns(m: MatchResult, from: Int): List<Color> = (from until from + 5).map { color(m.groupValues[it]) }

    @Test
    fun every_ios_palette_row_matches_tok() {
        val src = ios.resolve("DesignTokens.swift").readText()
        val rows = Regex("""^\s*"(\w+)": $column,\s*$""", RegexOption.MULTILINE).findAll(src).toList()
        assertEquals("iOS palette rows parsed", 26, rows.size)
        assertEquals("every Tok has an iOS row", Tok.entries.map { it.name }.toSet(), rows.map { it.groupValues[1] }.toSet())
        for (row in rows) {
            val tok = Tok.valueOf(row.groupValues[1])
            for ((i, c) in columns(row, 2).withIndex()) {
                assertEquals("${tok.name} ${sets[i]}", c, tok.of(sets[i]))
            }
        }
    }

    @Test
    fun every_ios_group_tone_matches_group_tone() {
        val src = ios.resolve("GroupsScreen.swift").readText()
        val cases = Regex("""case \.(\w+): return \(fill: $column, letter: $column\)""").findAll(src).toList()
        assertEquals("iOS tone cases parsed", 6, cases.size)
        assertEquals("the same six tones, in the same order", GroupTone.entries.map { it.name.lowercase() }, cases.map { it.groupValues[1] })
        for (case in cases) {
            val tone = GroupTone.valueOf(case.groupValues[1].uppercase())
            for ((i, c) in columns(case, 2).withIndex()) assertEquals("$tone fill ${sets[i]}", c, tone.fillOf(sets[i]))
            for ((i, c) in columns(case, 7).withIndex()) assertEquals("$tone letter ${sets[i]}", c, tone.letterOf(sets[i]))
        }
    }
}
