package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-234 wave (decision 102): a glass card pads the same on both shells. Each card that pads other than the default 16
 * is paired with its iOS twin; the iOS value is READ from the Swift source (`.glassCard(padding: N)`, plus a
 * `.padding(N)` written just before it, as People's recent row does on a card padded 0) and the Android call must ask
 * for the same. It replaces AppearanceTest's allowlist of files that may set their own padding: every padded card on
 * either shell must be claimed by a pair (or, on iOS, by a named exception), so a new difference fails here. The iOS
 * folder is a test input in build.gradle.kts, so a Swift change reruns it.
 */
class CardPaddingParityTest {
    private val ios = java.io.File("../../ios/Skipti/App")
    private val android = java.io.File("src/main/java/com/vardrlabs/skipti")

    /** Where a card's call sits relative to its anchor (a unique line of the card's own code). */
    private enum class Side { AFTER, BEFORE }

    private data class Site(val file: String, val anchor: String, val side: Side)

    private data class Pair(val what: String, val ios: Site, val android: Site)

    private val pairs = listOf(
        Pair(
            "Activity feed",
            Site("ActivityFeed.swift", "ForEach(Array(entries.enumerated()), id: \\.element.id) { i, entry in", Side.AFTER),
            Site("ActivityFeed.kt", "entries.forEachIndexed { i, entry ->", Side.BEFORE),
        ),
        Pair(
            "Overview's people",
            Site("OverviewScreen.swift", "ForEach(Array(people.enumerated()), id: \\.offset) { i, p in", Side.AFTER),
            Site("OverviewTab.kt", "people.forEachIndexed { i, p ->", Side.BEFORE),
        ),
        Pair(
            "a group's recent expenses",
            Site("OverviewScreen.swift", "ForEach(Array(recent.enumerated()), id: \\.offset) { i, e in", Side.AFTER),
            Site("OverviewTab.kt", "recent.forEachIndexed { i, e ->", Side.BEFORE),
        ),
        Pair(
            "Phones' list",
            Site("Phones.swift", "ForEach(Array(rows.enumerated()), id: \\.element.id) { i, row in", Side.AFTER),
            Site("Phones.kt", "shown.rows.forEachIndexed { i, row ->", Side.BEFORE),
        ),
        Pair(
            "a removal's option card",
            Site("RemovalFlow.swift", "private func option(_ value: Choice, _ title: String, _ sub: String) -> some View {", Side.AFTER),
            Site("RemovalFlow.kt", ".then(if (selected) Modifier.border(2.dp, Tok.accent(dark), ThemeArt.cardShape(Appearance.theme)) else Modifier)", Side.AFTER),
        ),
        Pair(
            "record the payments",
            Site("RemovalFlow.swift", "Text(verbatim: RolesCopy.recordPaymentsTitle)", Side.AFTER),
            Site("RemovalFlow.kt", "Text(RolesCopy.RECORD_PAYMENTS_TITLE, style = titleStyle(dark))", Side.AFTER),
        ),
        Pair(
            "redistribute's people",
            Site("RemovalFlow.swift", "ForEach(Array(m.candidates.enumerated()), id: \\.element) { n, id in", Side.AFTER),
            Site("RemovalFlow.kt", "m.candidates.forEachIndexed { n, id ->", Side.BEFORE),
        ),
        Pair(
            "People's search results",
            Site("People.swift", "ForEach(Array(results.enumerated()), id: \\.element.id) { i, person in", Side.AFTER),
            Site("People.kt", "found.forEachIndexed { i, person ->", Side.BEFORE),
        ),
        Pair(
            "Add person's recent row",
            Site("People.swift", "private func recentRow(_ r: RecentPerson) -> some View {", Side.AFTER),
            Site("People.kt", ".alpha(RecentRow.alpha(busy, isAdded))", Side.AFTER),
        ),
        Pair(
            "the expense list's group card (D-234 L12-d)",
            Site("ExpenseListView.swift", "ForEach(Array(section.expenses.enumerated()), id: \\.element.id) { index, expense in", Side.AFTER),
            Site("ExpenseListScreen.kt", "section.expenses.forEachIndexed { index, expense ->", Side.BEFORE),
        ),
        Pair(
            "a licence's text panel (D-225 Design 6)",
            Site("About.swift", "Text(verbatim: Licences.reflow(text))", Side.AFTER),
            Site("About.kt", "Licences.reflow(text),", Side.AFTER),
        ),
        Pair(
            "the shared list card (D-225 Design 1)",
            Site("ListRows.swift", "_VariadicView.Tree(ListCardRows()) { content }", Side.AFTER),
            Site("ListRows.kt", "val rows = ListCardScope().apply(build).rows", Side.AFTER),
        ),
        Pair(
            "Your spending's expenses (D-249)",
            Site("Spending.swift", "ForEach(Array(l.out.rows.enumerated()), id: \\.element.id) { i, row in", Side.AFTER),
            Site("Spending.kt", "l.out.rows.forEachIndexed { i, row ->", Side.BEFORE),
        ),
        Pair(
            "the Ledger page's rows (D-249, decision 193)",
            Site("Spending.swift", "LucideIcon(glyph: .check, size: 20).foregroundStyle(Tokens.accent)", Side.AFTER),
            Site("Spending.kt", ".selectable(selected = isChosen, role = Role.RadioButton, onClick = {", Side.AFTER),
        ),
        Pair(
            "the welcome's emoji choices (D-245 W4)",
            Site("Welcome.swift", "EmojiChoices(chosen: stage.staged ?? saved, open: moreOpen, onMore: { moreOpen = true }) { e in", Side.AFTER),
            Site("Welcome.kt", "EmojiChoices(s.stage.staged ?: s.saved, s.moreOpen, dark, onMore = { s.moreOpen = true }) { e ->", Side.BEFORE),
        ),
    )

    /** iOS's padded cards whose Android twin is not a glass card: People's search field and Add person's fields are
     *  text fields on Android (an outlined field with its own label), so there is no card padding to pair. */
    private val iosNotACardTwin = listOf(
        Site("People.swift", "TextField(PeopleCopy.queryPrompt, text: \$query)", Side.AFTER),
        Site("People.swift", "content().font(TypeRole.body()).padding(12)", Side.AFTER),
    )

    // `.padding(N)` written just before the card adds to its padding (People's recent row: `.padding(10)` on 0).
    private val swiftCall = Regex("""(?:\.padding\((\d+)\)\s*)?\.glassCard\((?:padding: (\d+))?\)""")
    private val swiftPadded = Regex("""\.glassCard\(padding: (\d+)\)""")
    private val kotlinCall = Regex("""\.glassCard\(dark(?:, padding = (\d+))?\)""")
    private val kotlinPadded = Regex("""glassCard\([^)]*padding =""")

    private fun text(file: String, swift: Boolean) = (if (swift) ios else android).resolve(file).readText()

    /** The card call [site] names: the first after its anchor or the last before it, within [reach] characters. */
    private fun call(site: Site, swift: Boolean): MatchResult {
        val src = text(site.file, swift)
        val at = src.indexOf(site.anchor)
        assertTrue("${site.file}: anchor not found: ${site.anchor}", at >= 0)
        assertEquals("${site.file}: anchor not unique: ${site.anchor}", -1, src.indexOf(site.anchor, at + 1))
        val regex = if (swift) swiftCall else kotlinCall
        val reach = if (swift) 4000 else 300
        val m = when (site.side) {
            Side.AFTER -> regex.find(src, at + site.anchor.length)?.takeIf { it.range.first - (at + site.anchor.length) <= reach }
            Side.BEFORE -> regex.findAll(src.substring(0, at)).lastOrNull()?.takeIf { at - it.range.last <= reach }
        }
        assertTrue("${site.file}: no glass card ${site.side.name.lowercase()} '${site.anchor}' within $reach", m != null)
        return m!!
    }

    private fun iosPadding(site: Site): Int {
        val m = call(site, swift = true)
        val before = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        return before + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 16)
    }

    private fun androidPadding(site: Site): Int =
        call(site, swift = false).groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: 16

    @Test
    fun every_paired_card_pads_as_ios_does() {
        val wrong = pairs.mapNotNull { p ->
            val (i, a) = iosPadding(p.ios) to androidPadding(p.android)
            if (i == a) null else "${p.what}: iOS ${p.ios.file} pads $i, Android ${p.android.file} pads $a"
        }
        assertEquals("cards that pad differently from their iOS twin", emptyList<String>(), wrong)
    }

    /** The reader is real: the values it reads today (decision 102's nine sites and the three earlier ones). */
    @Test
    fun the_ios_reader_reads_the_swift_values() {
        assertEquals(
            // D-245: + the welcome's emoji choices, 10. D-249: + Your spending's expenses and the Ledger page's rows, 14 each.
            listOf(14, 14, 14, 14, 14, 14, 12, 14, 10, 14, 12, 0, 14, 14, 10),
            pairs.map { iosPadding(it.ios) },
        )
    }

    /** Every padded call on either shell is claimed, so a new card that pads its own way must join a pair. */
    @Test
    fun every_padded_card_is_paired() {
        val claimedIos = (pairs.map { it.ios } + iosNotACardTwin).map { it.file to call(it, swift = true).range.last }.toSet()
        val iosFiles = ios.listFiles { f -> f.name.endsWith(".swift") }!!
        val unclaimedIos = iosFiles.flatMap { f ->
            val src = f.readText()
            swiftPadded.findAll(src)
                .filter { m -> !src.substring(src.lastIndexOf('\n', m.range.first) + 1, m.range.first).trimStart().startsWith("//") }
                .filter { m -> (f.name to m.range.last) !in claimedIos }
                .map { m -> "${f.name}:${src.substring(0, m.range.first).count { it == '\n' } + 1}" }
                .toList()
        }
        assertEquals("iOS cards with their own padding and no Android pair", emptyList<String>(), unclaimedIos)

        val claimedAndroid = pairs.map { it.android }.map { it.file to call(it, swift = false).range.first }.toSet()
        val unclaimedAndroid = android.listFiles { f -> f.name.endsWith(".kt") }!!.flatMap { f ->
            val src = f.readText()
            kotlinPadded.findAll(src)
                .filter { m -> (f.name to m.range.first - 1) !in claimedAndroid }
                .map { m -> "${f.name}:${src.substring(0, m.range.first).count { it == '\n' } + 1}" }
                .toList()
        }
        assertEquals("Android cards with their own padding and no iOS pair", emptyList<String>(), unclaimedAndroid)
    }
}
