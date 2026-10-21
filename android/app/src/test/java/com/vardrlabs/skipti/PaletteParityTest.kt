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

    /** D-253 (the store look: the same seeded groups in different tones on the two phones): a group's tone is picked
     *  by one function on both shells, the id's first byte modulo six into the same six tones. iOS's `GroupTone.of` is
     *  read here, and fixed ids pick the same tone index on both (worked by hand: 0x02 -> 2 teal, 0x05 -> 5 pink, 0x01
     *  -> 1 blue, 0xa1 = 161 -> 5 pink, 0xff = 255 -> 3 amber, a non-hex start -> 0 coral). */
    @Test
    fun a_groups_tone_is_picked_as_ios_picks_it() {
        val src = ios.resolve("GroupsScreen.swift").readText()
        val of = src.substringAfter("static func of(_ groupID: String) -> GroupTone {").substringBefore("\n    }\n")
        assertEquals(
            "iOS's pick, verbatim",
            "\n        let byte = Int(groupID.prefix(2), radix: 16) ?? 0\n        return allCases[byte % allCases.count]",
            of,
        )
        val kt = java.io.File("src/main/java/com/vardrlabs/skipti/GroupsTab.kt").readText()
            .substringAfter("fun of(groupId: String): GroupTone {").substringBefore("\n        }\n")
        assertEquals(
            "Android's pick, the same steps",
            "\n            val byte = groupId.take(2).toIntOrNull(16) ?: 0\n            return entries[byte % entries.size]",
            kt,
        )
        val tail = "8c2fba8e39317ca229dc1fd08e9854"
        for ((start, index) in listOf("02" to 2, "05" to 5, "01" to 1, "a1" to 5, "ff" to 3, "zz" to 0)) {
            assertEquals(start, index, GroupTone.of(start + tail).ordinal)
        }
        // The negative: one byte apart is another tone.
        assertEquals(GroupTone.TEAL, GroupTone.of("02$tail"))
        assertEquals(GroupTone.AMBER, GroupTone.of("03$tail"))
    }
}
