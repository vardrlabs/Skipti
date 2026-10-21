package com.vardrlabs.skipti

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.OverviewGroupIn
import uniffi.skipti_core.SettlementInput
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiCategoryTreeDefault
import uniffi.skipti_core.ffiOverview
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** D-180: the twin of iOS's overview tests (BalanceLineTests,
 *  AppearanceTests.testTheHerosChipsStayReadableOnEveryStop). */
class OverviewTest {

    @Test
    fun overview_words_and_percents_match_ios() {
        assertEquals("59%", OverviewCopy.percent(5903u))
        assertEquals("35%", OverviewCopy.percent(3450u))
        assertEquals("<1%", OverviewCopy.percent(49u))
        assertEquals("1%", OverviewCopy.percent(50u))
        assertEquals("You owe €32.00", OverviewCopy.chip(-3_200, "€32.00"))
        assertEquals("You get back ₹900.00", OverviewCopy.chip(90_000, "₹900.00"))
        assertEquals("1 group", OverviewCopy.groups(1))
        assertEquals("you owe", OverviewCopy.verb(-1))
        assertEquals("4 people · settles in INR", OverviewCopy.settlesIn(4, "INR"))
    }

    @Test
    fun category_levels_split_the_tree_in_two() {
        val tree = ffiCategoryTreeDefault()
        val parents = CategoryLevels.parents(tree)
        assertEquals(13, parents.size)
        val travel = parents.first { it.second == "Travel" }.first
        assertEquals(
            listOf("Flights", "Trains", "Buses", "Taxis", "Fuel", "Tolls", "Parking", "Rentals"),
            CategoryLevels.leaves(travel, tree).map { it.second },
        )
        val trains = CategoryLevels.leaves(travel, tree).first { it.second == "Trains" }.first
        assertEquals(travel, CategoryLevels.parent(trains, tree))
        assertNull(CategoryLevels.parent(travel, tree))
        assertNull(CategoryLevels.parent(null, tree))
        assertTrue(CategoryLevels.leaves(null, tree).isEmpty())
        assertEquals("No category", CategoryLevels.name(null, tree))
    }

    /** D-181: the twin of iOS's testTheCategoryPickerKeepsASubCategoryOnlyUnderItsParent. */
    @Test
    fun the_category_picker_keeps_a_sub_category_only_under_its_parent() {
        val tree = ffiCategoryTreeDefault()
        val parents = CategoryLevels.parents(tree)
        val travel = parents.first { it.second == "Travel" }.first
        val stay = parents.first { it.second == "Stay" }.first
        val trains = CategoryLevels.leaves(travel, tree).first { it.second == "Trains" }.first
        assertEquals(trains, CategoryLevels.keep(trains, travel, tree))
        assertNull(CategoryLevels.keep(trains, stay, tree))
        assertNull(CategoryLevels.keep(trains, null, tree))
        assertNull(CategoryLevels.keep(null, travel, tree))
        assertEquals("Choose one to file it under Travel.", CategoryCopy.hint("Travel"))
        assertEquals(
            listOf("Category", "Sub-category", "None", "Choose one"),
            listOf(CategoryCopy.CATEGORY, CategoryCopy.SUBCATEGORY, CategoryCopy.NONE, CategoryCopy.CHOOSE),
        )
    }

    /** meera 522 -> coral, ana 304 -> sky, sam 321 -> amber, kiran 533 -> pink. */
    @Test
    fun a_persons_tone_comes_from_their_name() {
        assertEquals(GroupTone.CORAL, personTone("Meera"))
        assertEquals(GroupTone.SKY, personTone("Ana"))
        assertEquals(GroupTone.AMBER, personTone("sam"))
        assertEquals(GroupTone.PINK, personTone("KIRAN"))
    }

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    @Test
    fun the_heros_chips_stay_readable_on_every_stop() {
        val k = Aurora.HERO_CHIP_ALPHA
        for (set in ThemeSet.entries) {
            val text = Tok.onHero.of(set)
            for (tok in listOf(Tok.heroStart, Tok.heroMid, Tok.heroEnd)) {
                val stop = tok.of(set)
                val film = Color(
                    stop.red + k * (text.red - stop.red),
                    stop.green + k * (text.green - stop.green),
                    stop.blue + k * (text.blue - stop.blue),
                )
                val (a, b) = luminance(text) to luminance(film)
                assertTrue("$set $tok", (max(a, b) + 0.05) / (min(a, b) + 0.05) >= 4.5)
            }
        }
    }

    // ---- D-218: a zero-net loop is hidden, through the shipped library (twin of
    // iOS's BalanceLineTests) ----

    private fun pid(n: Int) = "0".repeat(31) + n
    private val loopGroup = "9".repeat(32)

    /** A owes B `ab`, B owes C `bc`, as expenses (the payer is the creditor). */
    private fun chain(ab: Long, bc: Long) = listOf(
        ExpenseInput(pid(2), ab, "USD", listOf(IdAmount(pid(1), ab)), loopGroup, null),
        ExpenseInput(pid(3), bc, "USD", listOf(IdAmount(pid(2), bc)), loopGroup, null),
    )

    private fun line(expenses: List<ExpenseInput>, settlements: List<SettlementInput>, viewer: Int) =
        ffiBalanceLine(expenses, settlements, emptyList(), emptyList(), pid(viewer), loopGroup).firstOrNull()

    private fun overview(expenses: List<ExpenseInput>, settlements: List<SettlementInput>, viewer: Int) =
        ffiOverview(
            listOf(
                OverviewGroupIn(
                    loopGroup, pid(viewer),
                    (1..3).map { MemberWire(pid(it), listOf("Ann", "Bo", "Cy")[it - 1]) }, emptyList(),
                ),
            ),
            expenses, settlements, emptyList(),
        )

    /** L1: Settle all past a middle person. Everyone is at zero, so no card and no entry
     *  may remain. */
    @Test
    fun a_settle_all_loop_leaves_no_entries_and_no_people_cards() {
        val settle = listOf(SettlementInput(pid(1), pid(3), 1000, "USD", loopGroup))
        val expenses = chain(1000, 1000)
        for (viewer in 1..3) {
            val out = line(expenses, settle, viewer)!!
            assertEquals("viewer $viewer", 0L, out.viewerNetMinor)
            assertEquals("viewer $viewer sees no debts nobody has", emptyList<ShareOut>(), out.entries)
        }
        val home = overview(expenses, settle, 1)
        assertEquals(0, home.people.size)
        assertEquals(0, home.totals.size)
    }

    /** L2, the negative: a partial settle leaves a real debt, and only that one. */
    @Test
    fun a_partial_settle_leaves_only_the_real_debt() {
        val settle = listOf(SettlementInput(pid(1), pid(3), 600, "USD", loopGroup))
        val expenses = chain(1000, 600)
        val a = line(expenses, settle, 1)!!
        assertEquals(-400L, a.viewerNetMinor)
        assertEquals(listOf(ShareOut(pid(2), -400)), a.entries)
        val b = line(expenses, settle, 2)!!
        assertEquals(400L, b.viewerNetMinor)
        assertEquals(listOf(ShareOut(pid(1), 400)), b.entries)
        val c = line(expenses, settle, 3)!!
        assertEquals(0L, c.viewerNetMinor)
        assertEquals(emptyList<ShareOut>(), c.entries)
        val home = overview(expenses, settle, 1)
        assertEquals(1, home.people.size)
        assertEquals("Bo", home.people[0].name)
        assertEquals(-400L, home.people[0].netMinor)
        assertEquals(listOf(-400L), home.totals.map { it.amountMinor })
    }
}
