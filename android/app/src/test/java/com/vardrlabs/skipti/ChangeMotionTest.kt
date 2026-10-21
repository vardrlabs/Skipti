package com.vardrlabs.skipti

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-250 (decision 194): the fluidity sweep's Android half. ChangeMotion is iOS's table (read from ThemeMotion.swift),
 *  the pure decisions (a page's direction, a roll's direction, the rows kept while they leave, stable keys, the fill
 *  and the runes it lights) are pinned with negatives, and every helper reads the door and never power saving. Goldens
 *  are the ones stage 1 re-derived in Python (`.drafts/d250/goldens.py` -> `goldens.out`), not read back from the code. */
class ChangeMotionTest {
    private fun src(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
    private fun ios(name: String) = java.io.File("../../ios/Skipti/App/$name").readText()

    // ---- ChangeMotion: iOS's table, pointer for pointer ----

    @Test
    fun change_motion_is_the_ios_table() {
        val swift = ios("ThemeMotion.swift")
        val opens = Regex("""(?m)^enum ChangeMotion \{$""").findAll(swift).toList()
        assertEquals("ThemeMotion.swift has one enum ChangeMotion", 1, opens.size)
        val body = swift.substring(opens.single().range.first, swift.indexOf("\n}\n", opens.single().range.first))
        val lets = Regex("""(?m)^    static let (\w+) = (.+)$""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        // Every value POINTS at one that exists; the two of its own are the audit's.
        assertEquals(
            mapOf(
                "change" to "Double(ThemeArt.m(\"switchTravel\"))",
                "number" to "ToastMotion.fadeIn",
                "numberEase" to "ToastMotion.ease",
                "rise" to "ToastMotion.rise",
                "page" to "0.25",
                "fill" to "WelcomeProgress.sweepSeconds",
                "fade" to "0.15",
            ),
            lets,
        )
        // The pointers resolved on iOS's side, against Android's own values.
        val toast = swift.substring(swift.indexOf("enum ToastMotion {"))
        assertTrue("static let fadeIn = 0.2" in toast.substringBefore("\n}\n"))
        assertTrue("static let rise = 10.0" in toast.substringBefore("\n}\n"))
        assertTrue("static let ease: (Double, Double, Double, Double) = (0.2, 0.7, 0.3, 1.0)" in toast.substringBefore("\n}\n"))
        assertTrue("static let sweepSeconds = 0.9" in ios("Welcome.swift"))
        assertTrue("\"switchTravel\": 0.2" in ios("ThemeArt.swift") || Regex(""""switchTravel"\s*:\s*0\.2\b""").containsMatchIn(ios("ThemeArt.swift")))
        // Android's table points at the same things (no literal of its own but the two iOS has).
        val kt = src("ThemeMotion.kt").substringAfter("object ChangeMotion {").substringBefore("\n}\n")
        for (line in listOf(
            "val CHANGE: Double = ThemeArt.m(\"switchTravel\").toDouble()",
            "const val NUMBER = ToastMotion.FADE_IN",
            "val NUMBER_EASE: List<Double> = ToastMotion.EASE",
            "const val RISE = ToastMotion.RISE",
            "const val PAGE = 0.25",
            "const val FILL = WelcomeProgress.SWEEP_SECONDS",
            "const val FADE = 0.15",
        )) assertTrue("ChangeMotion: $line", line in kt)
    }

    @Test
    fun change_motion_goldens() {
        assertEquals(0.2, ChangeMotion.CHANGE, 1e-6)
        assertEquals(0.2, ChangeMotion.NUMBER, 0.0)
        assertEquals(listOf(0.2, 0.7, 0.3, 1.0), ChangeMotion.NUMBER_EASE)
        assertEquals(10.0, ChangeMotion.RISE, 0.0)
        assertEquals(0.25, ChangeMotion.PAGE, 0.0)
        assertEquals(0.9, ChangeMotion.FILL, 0.0)
        assertEquals(0.15, ChangeMotion.FADE, 0.0)
        assertEquals(
            listOf(200, 200, 250, 900, 150),
            ChangeMotion.Kind.entries.map { ChangeMotion.millis(it) },
        )
        for (kind in ChangeMotion.Kind.entries) {
            // Reduced: a cut, for every kind (iOS: nil). Not reduced: a tween of the kind's length, never a snap.
            assertTrue("$kind reduced is a snap", ChangeMotion.spec<Float>(kind, reduced = true) is SnapSpec)
            val moving = ChangeMotion.spec<Float>(kind, reduced = false)
            assertTrue("$kind moves", moving is TweenSpec)
            assertEquals(ChangeMotion.millis(kind), (moving as TweenSpec).durationMillis)
        }
        // The ease of change, page and fade is CSS ease-in-out (iOS's .easeInOut): 0.5 at the middle, slow at the ends.
        val inOut = ChangeMotion.easing(ChangeMotion.Kind.CHANGE)
        assertEquals(0.5f, inOut.transform(0.5f), 1e-3f)
        assertTrue(inOut.transform(0.1f) < 0.05f)
        // The number's ease is the toast's (0.2, 0.7, 0.3, 1): fast out of the start.
        assertTrue(ChangeMotion.easing(ChangeMotion.Kind.NUMBER).transform(0.25f) > 0.5f)
    }

    @Test
    fun the_fill_sweeps_as_the_welcome_does() {
        // goldens.out: share(t, 0.9) at eight times (stage 1's Python, the welcome's in-out ease).
        val goldens = listOf(0.0 to 0.0, 0.1 to 0.024691, 0.225 to 0.125, 0.45 to 0.5, 0.675 to 0.875, 0.8 to 0.975309, 0.9 to 1.0, 1.2 to 1.0)
        for ((t, share) in goldens) {
            val u = (t / ChangeMotion.FILL).coerceAtMost(1.0).toFloat()
            assertEquals("share at $t", share, ChangeMotion.FillEasing.transform(u).toDouble(), 1e-5)
        }
        // Negative: not linear (a quarter in is far from a quarter done), never outside 0...1.
        assertNotEquals(0.25, ChangeMotion.FillEasing.transform(0.25f).toDouble(), 0.05)
        for (i in 0..20) assertTrue(ChangeMotion.FillEasing.transform(i / 20f) in 0f..1f)
        // It sweeps only from a value already shown, and only with motion.
        assertTrue(FillMotion.sweeps(reduced = false, seen = 0.4))
        assertFalse("the first value lands at once", FillMotion.sweeps(reduced = false, seen = null))
        assertFalse("reduced: a cut", FillMotion.sweeps(reduced = true, seen = 0.4))
    }

    @Test
    fun the_ring_lights_runes_as_it_sweeps() {
        for (total in 1..8) for (done in 0..total) {
            val share = RuneRing.share(done, total)
            assertEquals("at rest $done of $total", RuneRing.lit(done, total), RuneRing.lit(share))
            // The float the sweep draws at rest (a Float made Double) still lights the count's runes.
            assertEquals(RuneRing.lit(done, total), RuneRing.litAt(share.toFloat().toDouble(), done, total))
            // Fix wave (M10): the Float the sweep rests on IS at rest (the count's integer rule decides it).
            assertTrue(RuneRing.atRest(share.toFloat().toDouble(), done, total))
            if (total > 1) assertFalse("a sweep's share between steps is not at rest", RuneRing.atRest(share + 0.5 / total, done, total))
        }
        var last = 0
        for (i in 0..1000) {
            val lit = RuneRing.lit(i / 1000.0)
            assertTrue("monotone", lit >= last)
            last = lit
        }
        assertEquals(18, RuneRing.lit(0.729167))
        assertEquals(17, RuneRing.lit(0.7291))
        assertEquals(0.0, RuneRing.share(3, 0), 0.0)
        assertEquals(1.0, RuneRing.share(9, 7), 0.0)
    }

    // ---- Pages ----

    @Test
    fun a_page_knows_its_way() {
        // The tabs to a pushed page and back.
        assertEquals(PageMove.PUSH, PageMotion.move(null, "account"))
        assertEquals(PageMove.BACK, PageMotion.move("account", null))
        // A page to its child and back to its parent.
        assertEquals(PageMove.PUSH, PageMotion.move("account", "account/appearance"))
        assertEquals(PageMove.BACK, PageMotion.move("account/appearance", "account"))
        assertEquals(PageMove.PUSH, PageMotion.move("about", "licences"))
        assertEquals(PageMove.BACK, PageMotion.move("licences", "about"))
        // A sheet (iOS presents Add, New group, an edit as one) rises and falls.
        assertEquals(PageMove.RISE, PageMotion.move(null, "add"))
        assertEquals(PageMove.FALL, PageMotion.move("add", null))
        assertEquals(PageMove.RISE, PageMotion.move("expense/e1", "expense-edit/e1/detail"))
        assertEquals(PageMove.FALL, PageMotion.move("expense-edit/e1/detail", "expense/e1"))
        // You's Start a group returns to You: the sheet falls.
        assertEquals(PageMove.FALL, PageMotion.move("new-group/you", "account"))
        // Negatives: a sheet never slides sideways, a push never rises, the same route is a cut.
        assertNotEquals(PageMove.PUSH, PageMotion.move(null, "new-group"))
        assertNotEquals(PageMove.RISE, PageMotion.move(null, "expense/e1"))
        assertEquals(PageMove.CUT, PageMotion.move("account", "account"))
        assertEquals(PageMove.CUT, PageMotion.move(null, null))
        // Inside a tab: a group's page pushes over the root and goes back; another tab is a cut.
        assertEquals(PageMove.PUSH, PageMotion.tabMove(sameTab = true, from = null, to = "group/g1"))
        assertEquals(PageMove.BACK, PageMotion.tabMove(sameTab = true, from = "group/g1", to = null))
        assertEquals(PageMove.CUT, PageMotion.tabMove(sameTab = false, from = null, to = null))
        assertEquals(PageMove.CUT, PageMotion.tabMove(sameTab = false, from = "group/g1", to = null))
        assertEquals(PageMove.CUT, PageMotion.tabMove(sameTab = true, from = null, to = null))
        // What a tab shows over its root.
        assertEquals("group/g1", TabPage(HomeTab.BALANCE, "g1", null).over)
        assertEquals("spending", TabPage(HomeTab.BALANCE, null, SpendingOpen.CARD).over)
        assertEquals(null, TabPage(HomeTab.EXPENSES, null, SpendingOpen.CARD).over)
        assertEquals("spending", TabPage(HomeTab.EXPENSES, null, SpendingOpen.HEADER).over)
        assertEquals(null, TabPage(HomeTab.SETTLE, "g1", null).over)
    }

    @Test
    fun the_shell_moves_its_pages_through_the_door() {
        val root = src("AppRoot.kt")
        val shell = root.substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        assertEquals("one read of the door in the shell", 1, shell.split("val reduce = rememberMotionState().reduce").size - 1)
        assertTrue(
            "the route's page",
            "transitionSpec = { pageTransform(PageMotion.move(initialState, targetState), reduce) }" in shell,
        )
        // Fix wave (I4): no move noted from inside the transition any more; the shell works it out as the route changes.
        assertFalse("PageMoves" in root)
        assertTrue("val move = remember(route) { PageMotion.move(routeMemo.route, route).also { routeMemo.route = route } }" in shell)
        assertTrue(
            "a tab's page",
            "transitionSpec = { pageTransform(PageMotion.tabMove(initialState.tab == targetState.tab, initialState.over, targetState.over), reduce) }" in shell,
        )
        // The pages read what they were composed for, never the shell's live route (the leaving page would turn into the
        // new one while it slides out).
        val routes = shell.substringAfter(") { shown ->").substringBefore("// D-220 (L-D217-3): the tab bar overlays the page")
        assertFalse("no `when (route)` left", "when (route)" in routes)
        assertFalse("no `val r = route`", "val r = route" in routes)
        assertFalse("no `HomeRoutes.parent(route)`", "HomeRoutes.parent(route)" in routes)
        // Fix wave (I1, I3): every route's page is a SlidingPage (its own layer, a picture while it leaves, no touch).
        assertTrue("SlidingPage page@{" in routes)
        assertTrue("a page returns from its own content", "return@page" in routes)
        assertEquals(33, routes.split("return@page").size - 1)
        assertFalse("return@AnimatedContent" in routes)
        assertFalse("never a bare return out of the shell", Regex("""(?m)\breturn$""").containsMatchIn(routes))
        val tabs = shell.substringAfter(") { page ->")
        assertTrue("SlidingPage {" in tabs.substringBefore("val leavingBackdrop"))
        // Fix wave (M1): keyed by what the tab shows.
        assertTrue("contentKey = { it.key }," in shell)
        assertTrue("HomeTab.BALANCE, HomeTab.GROUPS -> page.group?.let { id ->" in tabs)
        assertFalse("the tab's page never reads the live group", "openGroup?.let { id ->" in tabs)
        // Fix wave (I4): the bar is drawn over the pages, outside them (never inside a sliding page), shown by the pure
        // rule, dropping down and fading over CHANGE (iOS's 0.2 s), a cut when reduced; under a sheet the shell's own copy.
        val bar = shell.substringAfter("val barMove = PageMotion.barMove(move)")
        assertTrue("visible = PageMotion.barShows(route)," in bar)
        assertTrue("enter = if (reduce) EnterTransition.None else when (barMove) {" in bar)
        assertTrue("exit = if (reduce) ExitTransition.None else when (barMove) {" in bar)
        assertTrue("PageMotion.BarMove.DROP -> slideInVertically(ChangeMotion.tween(ChangeMotion.Kind.CHANGE)) { it } + fadeIn(ChangeMotion.tween(ChangeMotion.Kind.CHANGE))" in bar)
        assertTrue("PageMotion.BarMove.DROP -> slideOutVertically(ChangeMotion.tween(ChangeMotion.Kind.CHANGE)) { it } + fadeOut(ChangeMotion.tween(ChangeMotion.Kind.CHANGE))" in bar)
        assertFalse("the bar is never inside a page", "SkiptiTabBar(" in routes)
        assertFalse(".animateEnterExit(" in root)
        assertTrue("if (PageMotion.barUnderSheet(route, move, entering = routeSlide == PageSlide.ENTERING)) {" in shell)
        // The transform: a cut when reduced, before anything else.
        val transform = src("MotionViews.kt").substringAfter("fun AnimatedContentTransitionScope<*>.pageTransform(").substringBefore("\n}\n")
        assertTrue(transform.lines()[1].trim() == "if (reduce || move == PageMove.CUT) return EnterTransition.None togetherWith ExitTransition.None")
        // The look's fixes: only the page a tab is going to records into the shell's backdrop (a layer drawn by reference
        // shows its last recording on both pages); sliding pages take the ground themselves (they sit on the shell's);
        // the remaining source redraws when another goes.
        assertTrue("CompositionLocalProvider(LocalShellBackdrop provides if (current) shellBackdrop else leavingBackdrop)" in tabs)
        assertTrue("val current = slide != PageSlide.LEAVING" in tabs)
        // Fix wave (I1): one opaque ground a tab slide, the page on top's.
        assertTrue("val ownGround = PageMotion.ownGround(slide, tabTarget.over)" in tabs)
        assertTrue("Box(if (ownGround) Modifier.fillMaxSize().auroraBackground(dark) else Modifier.fillMaxSize())" in tabs)
        assertFalse("if (sliding)" in tabs)
        val backdrop = src("Backdrop.kt").substringAfter("fun Modifier.backdropSource(").substringBefore("\n}\n")
        assertTrue("backdrop.sourcesGone++" in backdrop && "val gone = backdrop.sourcesGone" in backdrop)
        // ChoicePage's window leaves as it came.
        val window = src("ListRows.kt").substringAfter("fun PushedWindow(").substringBefore("\n}\n")
        assertTrue("exit = if (motion.reduce) ExitTransition.None else slideOutHorizontally(" in window)
        assertTrue("Dialog(onDismissRequest = close," in window)
        assertTrue("if (motion.reduce) {\n            onClose()" in window)
    }

    // ---- Fix wave: pages on their own layers, the bar, the tab's key ----

    @Test
    fun a_page_knows_where_it_is_in_its_slide() {
        assertEquals(PageSlide.ENTERING, PageSlide.of(EnterExitState.PreEnter, EnterExitState.Visible))
        assertEquals(PageSlide.STILL, PageSlide.of(EnterExitState.Visible, EnterExitState.Visible))
        assertEquals(PageSlide.LEAVING, PageSlide.of(EnterExitState.Visible, EnterExitState.PostExit))
        assertEquals("a leave turned back is still leaving until it is told otherwise", PageSlide.LEAVING, PageSlide.of(EnterExitState.PreEnter, EnterExitState.PostExit))
        // Only the page leaving is frozen, holds its art and swallows touches; only a page at rest takes the art holder.
        assertEquals(listOf(true, true, false), PageSlide.entries.map { it.takesTouches })
        assertEquals(listOf(false, false, true), PageSlide.entries.map { it.frozen })
        assertEquals(listOf(false, false, true), PageSlide.entries.map { it.artHeld })
        assertEquals(listOf(true, false, false), PageSlide.entries.map { it.artClaims })
        // Negatives: the page coming in takes touches and keeps its art running; the page at rest is never frozen.
        assertTrue(PageSlide.ENTERING.takesTouches)
        assertFalse(PageSlide.ENTERING.artHeld)
        assertFalse(PageSlide.STILL.frozen)
    }

    @Test
    fun a_sliding_page_is_a_layer_and_its_leaving_picture_is_taken_once() {
        val views = src("MotionViews.kt")
        val page = views.substringAfter("fun AnimatedVisibilityScope.SlidingPage(").substringBefore("\n}\n")
        assertTrue("val slide = PageSlide.of(transition.currentState, transition.targetState)" in page)
        assertTrue("CompositionLocalProvider(LocalPageSlide provides slide)" in page)
        val layer = views.substringAfter("private fun Modifier.pageLayer(slide: PageSlide): Modifier {").substringBefore("\n}\n")
        // Its own layer first, so the slide moves a RenderNode.
        assertTrue(layer.substringAfter("return this").trim().startsWith(".graphicsLayer()"))
        assertTrue("if (slide.takesTouches) Modifier else Modifier.pointerInput(Unit) { swallowTouches() }" in layer)
        // The picture: recorded once (offscreen: one textured quad a frame), then drawn without the page drawing itself.
        assertTrue("if (!kept[0]) {\n                copy.compositingStrategy = CompositingStrategy.Offscreen\n                copy.record { this@drawWithContent.drawContent() }\n                kept[0] = true" in layer)
        assertEquals("drawContent() runs only when not frozen, or into the picture", 2, layer.split("drawContent()").size - 1)
        assertTrue("awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }" in views)
        // The art holds while its page leaves, never jumping to its still frame; a sliding page never takes the holder.
        val motion = src("ThemeMotion.kt")
        val canvas = motion.substringAfter("fun Modifier.artCanvas(").substringBefore("\n}\n")
        assertTrue("val held = LocalPageSlide.current.artHeld" in canvas)
        assertTrue("LaunchedEffect(tick, held) {\n        if (!tick || held) return@LaunchedEffect" in canvas)
        assertTrue("drawBehind { draw(ArtCanvas.seconds(tick, t, still)) }" in canvas)
        val turn = motion.substringAfter("fun Modifier.artTurn(").substringBefore("\n}\n")
        assertTrue("if (held) return@LaunchedEffect\n        turning = RingTurn.STILL" in turn)
        val curtains = motion.substringAfter("private fun Modifier.curtainsGround(").substringBefore("\n}\n")
        assertTrue("val claims = LocalPageSlide.current.artClaims" in curtains)
        assertTrue("if (art != null && content != null && claims && owns()) {" in curtains)
        val art = motion.substringAfter("fun Modifier.pageArt(dark: Boolean)").substringBefore("\n}")
        assertTrue("if (slide != PageSlide.STILL && measured[0]) return@onGloballyPositioned" in art)
    }

    @Test
    fun the_bar_shows_on_the_tabs_alone() {
        assertTrue(PageMotion.barShows(null))
        assertFalse("a pushed page hides it", PageMotion.barShows("account"))
        assertFalse("a sheet covers it", PageMotion.barShows("add"))
        // The bar's way: a push drops it, Back brings it up, a sheet swaps it for the shell's copy under the sheet.
        assertEquals(PageMotion.BarMove.DROP, PageMotion.barMove(PageMotion.move(null, "account")))
        assertEquals(PageMotion.BarMove.DROP, PageMotion.barMove(PageMotion.move("account", null)))
        assertEquals(PageMotion.BarMove.SWAP, PageMotion.barMove(PageMotion.move(null, "add")))
        assertEquals(PageMotion.BarMove.AFTER_SHEET, PageMotion.barMove(PageMotion.move("add", null)))
        assertEquals(PageMotion.BarMove.CUT, PageMotion.barMove(PageMove.CUT))
        // The shell's copy: under a sheet (rising or up), and while one falls back to the tabs; never on a push.
        assertTrue(PageMotion.barUnderSheet("add", PageMove.RISE, entering = false))
        assertTrue(PageMotion.barUnderSheet(null, PageMove.FALL, entering = true))
        assertFalse("landed: the shell's own bar again", PageMotion.barUnderSheet(null, PageMove.FALL, entering = false))
        assertFalse("a push's page: no bar under it", PageMotion.barUnderSheet("account", PageMove.PUSH, entering = false))
        assertFalse("Back to the tabs: the bar comes up itself", PageMotion.barUnderSheet(null, PageMove.BACK, entering = true))
        assertFalse(PageMotion.barUnderSheet(null, PageMove.CUT, entering = false))
        // iOS's: the bar drops over 0.2 s (ChangeMotion.CHANGE), not the page's 0.25.
        assertEquals(200, ChangeMotion.millis(ChangeMotion.Kind.CHANGE))
    }

    @Test
    fun one_ground_a_tab_slide() {
        // A group's page pushed over the root: the page coming in has the ground, the root under it none.
        assertTrue(PageMotion.ownGround(PageSlide.ENTERING, targetOver = "group/g1"))
        assertFalse(PageMotion.ownGround(PageSlide.LEAVING, targetOver = "group/g1"))
        // Back: the page going out has it, the root coming back under it none.
        assertTrue(PageMotion.ownGround(PageSlide.LEAVING, targetOver = null))
        assertFalse(PageMotion.ownGround(PageSlide.ENTERING, targetOver = null))
        // At rest, none (the shell's ground is the page's).
        assertFalse(PageMotion.ownGround(PageSlide.STILL, targetOver = "group/g1"))
        assertFalse(PageMotion.ownGround(PageSlide.STILL, targetOver = null))
        // Never two at once.
        for (over in listOf(null, "group/g1")) {
            assertEquals(1, listOf(PageSlide.ENTERING, PageSlide.LEAVING).count { PageMotion.ownGround(it, over) })
        }
    }

    @Test
    fun a_tab_is_keyed_by_what_it_shows() {
        // A group left open on Balance, cleared while Expenses shows: Expenses' key does not change (no reload).
        assertEquals(TabPage(HomeTab.EXPENSES, "g1", null).key, TabPage(HomeTab.EXPENSES, null, null).key)
        assertEquals(TabPage(HomeTab.SETTLE, "g1", SpendingOpen.CARD).key, TabPage(HomeTab.SETTLE, null, null).key)
        assertEquals(TabPage(HomeTab.EXPENSES, null, SpendingOpen.CARD).key, TabPage(HomeTab.EXPENSES, null, null).key)
        // Negatives: what the tab shows changes the key.
        assertNotEquals(TabPage(HomeTab.BALANCE, "g1", null).key, TabPage(HomeTab.BALANCE, null, null).key)
        assertNotEquals(TabPage(HomeTab.BALANCE, "g1", null).key, TabPage(HomeTab.BALANCE, "g2", null).key)
        assertNotEquals(TabPage(HomeTab.EXPENSES, null, SpendingOpen.HEADER).key, TabPage(HomeTab.EXPENSES, null, null).key)
        assertNotEquals(TabPage(HomeTab.BALANCE, null, null).key, TabPage(HomeTab.GROUPS, null, null).key)
    }

    @Test
    fun a_group_page_slides_in_drawn() {
        assertEquals(100L, GroupPageKeep.HOLD_MS)
        assertTrue("a first visit waits for its read", GroupPageKeep.holds(kept = false))
        assertFalse("a page read before opens at once", GroupPageKeep.holds(kept = true))
        assertTrue(GroupPageKeep.drawn(keptAtOpen = true, read = false, failed = false))
        assertTrue(GroupPageKeep.drawn(keptAtOpen = false, read = true, failed = false))
        assertTrue("an error must show", GroupPageKeep.drawn(keptAtOpen = false, read = false, failed = true))
        assertFalse("nothing to draw yet: the cards wait to fade in", GroupPageKeep.drawn(keptAtOpen = false, read = false, failed = false))
        val root = src("AppRoot.kt")
        assertTrue("withTimeoutOrNull(GroupPageKeep.HOLD_MS) { read.await() }" in root)
        assertEquals(2, root.split("onOpenGroup = ::openGroupPage,").size - 1)
        assertTrue("keep = groupPages.getOrPut(id) { GroupPageKeep() }," in root)
        val page = src("OverviewTab.kt")
        assertTrue("val kept = remember(groupId) { keep.last }\n    val keptAtOpen = kept != null" in page)
        assertTrue("runCatching { GroupPageKeep.read(vaultPath, groupId) }" in page)
        assertTrue("if (GroupPageKeep.drawn(keptAtOpen, named != null, error != null || loadFailed)) 1f else 0f," in page)
    }

    /** D-252 fix wave (I1): a kept read seeds the page's state; it is never written into it while the page draws. A
     *  write during composition into state made in that composition is not seen by what already read it: a read-only
     *  copy opened a second time drew without its banner, with Add people and Settle (stage 3's finding). */
    @Test
    fun a_group_page_never_writes_its_state_while_drawing() {
        val page = src("OverviewTab.kt").substringAfter("fun GroupPage(").substringBefore("\n}\n")
        // Every remember block in the page: none calls show() (or assigns the page's state) while composing.
        var at = page.indexOf("remember")
        var blocks = 0
        while (at >= 0) {
            val open = page.indexOf('{', at)
            // `remember(keys) {` or `remember {`, never a call that merely names remember (rememberScrollState()).
            val word = page.substring(at, minOf(page.length, at + 9))
            if (word == "remember " || word == "remember(" || word == "remember{") {
                var depth = 0
                var i = open
                while (true) {
                    if (page[i] == '{') depth++
                    if (page[i] == '}') { depth--; if (depth == 0) break }
                    i++
                }
                val block = page.substring(open, i + 1)
                assertFalse("a remember block draws a read: $block", "show(" in block)
                assertFalse("a remember block writes the page's state: $block", Regex("""\b(people|named|stats) = """).containsMatchIn(block))
                blocks++
            }
            at = page.indexOf("remember", at + 1)
        }
        assertTrue("the page's remember blocks were read ($blocks)", blocks >= 15)
        // Each state the read draws is seeded from the kept read, so the first frame of a reopened page is the copy.
        for (seed in listOf(
            "var named by remember(groupId) { mutableStateOf<NamedGroupWire?>(kept?.quad?.a) }",
            "var people by remember(groupId) { mutableStateOf(kept?.people ?: GroupPeople()) }",
            "var cardBalances by remember(groupId) { mutableStateOf(kept?.cardBalances ?: emptyMap()) }",
            "var historyNames by remember(groupId) { mutableStateOf(kept?.historyNames ?: emptyMap()) }",
            "var parkedHere by remember(groupId) { mutableStateOf(kept?.parked ?: false) }",
            "var stats by remember(groupId) { mutableStateOf<GroupStatsOut?>(kept?.quad?.b) }",
            "var lines by remember(groupId) { mutableStateOf(kept?.quad?.c ?: emptyList()) }",
            "var remaining by remember(groupId) { mutableIntStateOf(kept?.remaining ?: 0) }",
            "var recent by remember(groupId) { mutableStateOf(kept?.quad?.d ?: emptyList()) }",
            "var progress by remember(groupId) { mutableStateOf(kept?.progress ?: emptyList()) }",
            "var milestone by remember(groupId) { mutableStateOf<Milestones.Shown?>(kept?.milestone) }",
        )) assertTrue(seed, seed in page)
        // The copy's words and actions read the seeded state (declared after it), and show() runs only from the read.
        assertTrue(page.indexOf("val readOnly = people.readOnly") > page.indexOf("var people by remember(groupId)"))
        assertEquals("show() runs once: in the read's success", 1, Regex("""\n\s+show\(it\)\n""").findAll(page).count())
    }

    @Test
    fun the_roll_reads_left_to_right_on_one_line() {
        assertTrue(MoneyChange.rolls("₹900.00", "₹1,350.00", rtlLayout = false, lines = 1))
        assertTrue("words lead: LTR under an RTL locale too", MoneyChange.rolls("You owe ₹900.00", "You owe ₹1,350.00", rtlLayout = true, lines = 1))
        // Negatives: two lines (the ledger line keeps its wrap), right-to-left words, an RTL paragraph with no letters
        // (a sign moves to the other end there), Arabic-Indic digits.
        assertFalse(MoneyChange.rolls("₹900.00", "₹1,350.00", rtlLayout = false, lines = 2))
        assertFalse(MoneyChange.rolls("₹900.00", "₹1,350.00", rtlLayout = false, lines = 0))
        assertFalse(MoneyChange.rolls("₪900", "₪1,350", rtlLayout = true, lines = 1))
        assertFalse(MoneyChange.rolls("-₹900.00", "-₹1,350.00", rtlLayout = true, lines = 1))
        assertFalse(MoneyChange.rolls("אתה חייב 900", "אתה חייב 1,350", rtlLayout = false, lines = 1))
        assertFalse(MoneyChange.rolls("ر.س 900", "ر.س 1,350", rtlLayout = false, lines = 1))
        assertFalse(MoneyChange.rolls("٩٠٠", "١٬٣٥٠", rtlLayout = false, lines = 1))
        assertFalse("the old string decides too", MoneyChange.rolls("אתה 900", "You 1,350", rtlLayout = false, lines = 1))
        val money = src("MotionViews.kt").substringAfter("fun MoneyText(\n    text: AnnotatedString,").substringBefore("\n}\n")
        assertTrue("MoneyChange.rolls(roll.from.text, roll.to.text, rtl, layout[0]?.lineCount ?: 0)" in money)
        assertTrue("CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)" in money)
        // The text is always laid out (its wrap, alignment and semantics); the roll is drawn over its first line.
        assertTrue("maxLines = maxLines," in money && "textAlign = textAlign," in money)
        assertTrue("getLineLeft(0)" in money)
    }

    @Test
    fun the_crossfade_is_never_over_the_lock_and_the_pill_starts_where_it_was() {
        assertTrue(ThemeCrossfade.draws(locked = false))
        assertFalse(ThemeCrossfade.draws(locked = true))
        val views = src("MotionViews.kt")
        val overlay = views.substringAfter("fun ThemeCrossfadeOverlay() {").substringBefore("\n}\n")
        assertTrue(overlay.indexOf("if (!ThemeCrossfade.draws(locked = AppLock.locked)) {") < overlay.indexOf("Image("))
        assertTrue("LaunchedEffect(shot) { ThemeCrossfade.drop() }" in overlay)
        // The pill: from the segment the person left (the control made again by a mode change), else the chosen one,
        // once measured; nothing before.
        assertEquals(0, SlidingPills.drawnAt(start = 0, chosen = 2, known = setOf(0, 1, 2)))
        assertEquals(2, SlidingPills.drawnAt(start = null, chosen = 2, known = setOf(0, 1, 2)))
        assertEquals("the start not measured yet", 2, SlidingPills.drawnAt(start = 0, chosen = 2, known = setOf(2)))
        assertEquals(null, SlidingPills.drawnAt(start = null, chosen = 2, known = emptySet()))
        val pill = views.substringAfter("fun SlidingPill(").substringBefore("\n}\n")
        assertTrue("before the segments are measured, drawn in the draw", "places.at[start ?: chosen]?.let { r -> translate(r.left, r.top)" in pill)
        val hub = src("AccountHubScreen.kt")
        assertTrue("val fromMode = remember { ThemeCrossfade.fromMode?.takeIf { ThemeCrossfade.pending != null } }" in hub)
        assertTrue("ThemeCrossfade.change(view, reduce, fromMode = AppearanceMode.entries.indexOf(choice.chosen)) {" in hub)
    }

    // ---- Numbers, rows, keys ----

    @Test
    fun the_roll_knows_its_way() {
        assertFalse("first value counts up", MoneyChange.countsDown(null, 500))
        assertFalse(MoneyChange.countsDown(100, 500))
        assertTrue(MoneyChange.countsDown(500, 100))
        assertTrue("a balance owed growing is a fall", MoneyChange.countsDown(-100, -500))
        assertFalse(MoneyChange.countsDown(500, 500))
        // Slots keyed from the right: the cents keep their slot when the amount grows a digit.
        assertEquals(listOf(2 to '9', 1 to '.', 0 to '5'), MoneyChange.slots("9.5"))
        assertEquals(0, MoneyChange.slots("₹1,350.00").last().first)
        assertEquals(MoneyChange.slots("₹900.00").takeLast(3), MoneyChange.slots("₹1,350.00").takeLast(3))
        // Negative: keyed from the left, the cents would move.
        assertNotEquals(MoneyChange.slots("₹900.00").first().first, MoneyChange.slots("₹1,350.00").first().first)
    }

    @Test
    fun rows_that_leave_keep_their_place_until_they_have_gone() {
        assertEquals(listOf("a", "b", "c"), RowsMotion.merge(listOf("a", "b", "c"), listOf("a", "b", "c")))
        assertEquals("b stays where it was while it leaves", listOf("a", "b", "c"), RowsMotion.merge(listOf("a", "b", "c"), listOf("a", "c")))
        assertEquals(listOf("a", "b", "c"), RowsMotion.merge(listOf("a", "b", "c"), listOf("b", "c")))
        assertEquals(listOf("a", "b", "c"), RowsMotion.merge(listOf("a", "b", "c"), listOf("a", "b")))
        assertEquals("a new row in its place", listOf("a", "x", "c"), RowsMotion.merge(listOf("a", "c"), listOf("a", "x", "c")))
        assertEquals(listOf("b", "c", "a"), RowsMotion.merge(listOf("a", "b", "c"), listOf("b", "c", "a")))
        assertEquals("everything leaving", listOf("a", "b"), RowsMotion.merge(listOf("a", "b"), emptyList()))
        // Negative: nothing doubled, nothing lost.
        val merged = RowsMotion.merge(listOf("a", "b", "c", "d"), listOf("d", "x", "a"))
        assertEquals(merged.toSet().size, merged.size)
        assertEquals(setOf("a", "b", "c", "d", "x"), merged.toSet())
    }

    @Test
    fun rows_without_ids_get_unique_keys() {
        assertEquals(listOf("Sam|INR", "Sam|INR\u001F1", "Meera|INR", "Sam|INR\u001F2"), MotionKeys.unique(listOf("Sam|INR", "Sam|INR", "Meera|INR", "Sam|INR")))
        // Fix wave (M15): a person named "Sam#1" beside two "Sam"s keeps a key of their own.
        val named = MotionKeys.unique(listOf("Sam", "Sam", "Sam#1"))
        assertEquals(named.toString(), 3, named.toSet().size)
        val keys = MotionKeys.unique(listOf("a", "a", "b"))
        assertEquals(keys.toSet().size, keys.size)
        // A row keeps its key when one before it of another name goes.
        assertEquals(MotionKeys.unique(listOf("x", "Sam|INR"))[1], MotionKeys.unique(listOf("Sam|INR"))[0])
    }

    @Test
    fun a_settle_card_keeps_its_identity() {
        val whole = uniffi.skipti_core.ShareOut("p2", 4_500)
        val partial = uniffi.skipti_core.ShareOut("p2", 1_500)
        assertEquals("a partial keeps the card", SettleAll.cardId("g", "INR", whole), SettleAll.cardId("g", "INR", partial))
        assertNotEquals("another currency is another card", SettleAll.cardId("g", "INR", whole), SettleAll.cardId("g", "EUR", whole))
        assertNotEquals("another person", SettleAll.cardId("g", "INR", whole), SettleAll.cardId("g", "INR", uniffi.skipti_core.ShareOut("p3", 4_500)))
        assertEquals("g/INR/p2", SettleAll.cardId("g", "INR", whole))
    }

    @Test
    fun the_rows_are_keyed_by_what_they_are() {
        val settle = src("SettleAll.kt")
        assertTrue("MovingRows(debts, key = { it.first }, leave = RowLeave.SLIDE" in settle)
        assertTrue("Triple(SettleAll.cardId(named.group.id, line.currencyCode, it), line.currencyCode, it)" in settle)
        assertFalse("never by place", ".forEach { (currency, entry) ->" in settle)
        val overview = src("OverviewTab.kt")
        assertTrue("Home's people", "val keys = MotionKeys.unique(people.map { \"\${it.name}/\${it.currencyCode}\" })" in overview)
        assertTrue("MovingRows(people.zip(keys), key = { it.second })" in overview)
        assertFalse("people.forEachIndexed { i, p ->" in overview)
        assertTrue("the group page's recent rows", "MovingRows(recent, key = { it.id })" in overview)
        assertFalse("recent.forEachIndexed" in overview)
        assertTrue("the next card is one place", "key = { \"next\" }, leave = RowLeave.SLIDE" in overview)
        assertTrue("request cards", "MovingRows(cards, key = { it.id }" in overview)
        assertTrue("balance lines", "MovingRows(shownLines, key = { it.currencyCode }" in overview)
        val expenses = src("ExpenseListScreen.kt")
        assertTrue("MovingRows(carded.expenses, key = { it.id }, leave = RowLeave.SLIDE)" in expenses)
        assertFalse("section.expenses.forEachIndexed" in expenses)
        assertTrue("MovingRows(shownRows, key = { it.id })" in src("GroupScreens.kt"))
        // Fix wave (M4): a card or section moves as one, so its last row leaves with it.
        assertTrue("MovingRows(listOfNotNull(people.rows.takeIf { it.isNotEmpty() }), key = { \"people\" })" in src("GroupScreens.kt"))
        assertTrue("MovingRows(ExpenseSections.build(expenses, filter), key = { it.groupId ?: ExpenseListUi.JUST_ME_KEY }" in expenses)
        assertTrue("MovingRows(listOfNotNull(section.takeIf { it.expenses.isNotEmpty() }), key = { \"card\" }, leave = RowLeave.SLIDE)" in expenses)
        assertFalse(".forEach { section -> key(section.groupId" in expenses)
        assertTrue("MovingRows(listOfNotNull(lines.takeIf { it.isNotEmpty() }), key = { \"who-owes\" })" in overview)
        assertTrue("MovingRows(listOfNotNull(milestone), key = { \"milestone\" })" in overview)
        assertTrue("MovingRows(listOfNotNull(progress.takeIf { it.isNotEmpty() }), key = { \"settled\" })" in overview)
        assertFalse("if (progress.isNotEmpty()) SettledCard(progress, dark)" in overview)
        // Fix wave (M5): the group page's next card keeps "Settling" as it slides out.
        assertTrue("val settling = latch.settling(pick) || LocalRowLeaving.current" in src("Settled.kt"))
        // Fix wave (M14): Settle up's plan and the spending categories, keyed by what they are (iOS's twins).
        val t = uniffi.skipti_core.TransferOut("p1", "p2", 500)
        assertEquals("INR/p1/p2", SettleAll.transferId("INR", t))
        assertEquals("a partial keeps its row", SettleAll.transferId("INR", t), SettleAll.transferId("INR", uniffi.skipti_core.TransferOut("p1", "p2", 200)))
        assertNotEquals(SettleAll.transferId("INR", t), SettleAll.transferId("EUR", t))
        assertNotEquals(SettleAll.transferId("INR", t), SettleAll.transferId("INR", uniffi.skipti_core.TransferOut("p2", "p1", 500)))
        assertTrue("MovingRows(transfers, key = { (currency, t) -> SettleAll.transferId(currency, t) }, spacing = 6.dp)" in settle)
        assertFalse("transfers.forEach { (currency, t) ->" in settle)
        val spending = src("Spending.kt")
        assertEquals(2, spending.split("MovingRows(c.categories, key = { it.category ?: SpendingKeys.NONE }, spacing = 12.dp)").size - 1)
        assertFalse("c.categories.forEachIndexed" in spending)
        // The twins in iOS's source.
        assertTrue("ForEach(transfers, id: \\.id) { pair in" in ios("SettleAll.swift"))
        assertFalse("id: \\.offset" in ios("Spending.swift"))
    }

    // ---- Every helper reads the door, never power saving ----

    /** The checker: [body] reads the door and nothing the art reads. */
    private fun gated(body: String): Boolean =
        "rememberMotionState()" in body && "lowPower" !in body && "isStill(" !in body && "moment()" !in body &&
            "artTicks" !in body && "PowerManager" !in body

    @Test
    fun the_door_checker_catches_what_it_should() {
        assertTrue(gated("val reduce = rememberMotionState().reduce\nanimateFloatAsState(x, if (reduce) snap() else t)"))
        assertFalse("no door", gated("animateFloatAsState(x, tween(200))"))
        assertFalse("power saving", gated("val motion = rememberMotionState()\nval still = RuneLayer.isStill(motion.reduce, motion.lowPower)"))
        assertFalse("a moment's state stills nothing but reads the art clock", gated("val m = rememberMotionState().moment()"))
    }

    @Test
    fun every_change_helper_reads_the_door() {
        val views = src("MotionViews.kt")
        fun body(sig: String): String {
            assertTrue("found $sig", sig in views)
            return views.substringAfter(sig).substringBefore("\n}\n")
        }
        for (sig in listOf(
            "fun <T> MovingRows(", "fun RevealOnEnter(", "fun Reveal(", "fun MoneyText(\n    text: AnnotatedString,",
            "fun rememberFill(", "fun SlidingPill(", "fun ThemeCrossfadeOverlay(", "fun enabledAlpha(", "fun Modifier.movingSize(",
        )) assertTrue("$sig reads the door", gated(body(sig)))
        // The roll keys on core's integer and never makes a Double of it.
        val money = body("fun MoneyText(\n    text: AnnotatedString,")
        assertTrue("value: Long," in money)
        assertFalse("toDouble()" in money)
        assertFalse("Double" in money)
        assertTrue("if (!reduce && text.text != memo.rest.text) MoneyRoll(memo.rest, text, MoneyChange.countsDown(memo.value, value))" in money)
        // A card that is done slides out to the trailing edge (iOS's cardLeaves), fading, the rows closing up.
        val rows = body("fun <T> MovingRows(")
        assertTrue("RowLeave.SLIDE -> slideOutHorizontally(" in rows)
        // D-250 sabotage pin (row A21 survived): the slide goes to the TRAILING edge, a positive offset left to right and
        // a negative one right to left (iOS's `.move(edge: .trailing)`), never to the leading edge.
        assertTrue("val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr" in rows)
        assertTrue("to the trailing edge", "slideOutHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { w -> if (ltr) w else -w }" in rows)
        assertFalse("never to the leading edge", "{ w -> if (ltr) -w else w }" in rows)
        assertTrue("exit = if (motion.reduce) ExitTransition.None else when (leave)" in rows)
        // A row on its way out keeps looking as it last did (Settle up's card keeps "Recording…", from the look).
        assertTrue("CompositionLocalProvider(LocalRowLeaving provides !state.targetState)" in rows)
        assertTrue("if (latch.settling(key) || LocalRowLeaving.current) {" in src("SettleAll.kt"))
        // The crossfade: the door first; with no picture the change is made at once, never held back.
        val fade = views.substringAfter("fun change(view: View, reduce: Boolean, fromMode: Int? = null, change: () -> Unit) {").substringBefore("\n    }\n")
        assertTrue(fade.trim().lines()[2].trim().startsWith("if (reduce ||"))
        assertTrue("change()\n            return" in fade)
        assertFalse("lowPower" in fade)
        // Every caller is a person's own change: the theme cards, the mode control, the welcome's look; never sync.
        val callers = java.io.File("src/main/java/com/vardrlabs/skipti").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> Regex("""ThemeCrossfade\.change\(""").findAll(f.readText()).map { f.name } }.toList().sorted()
        assertEquals(listOf("AccountHubScreen.kt", "AccountHubScreen.kt", "Welcome.kt"), callers)
        assertFalse("ThemeCrossfade" in src("SettingsSync.kt"))
        // The buttons brighten and dim through the door; four bodies.
        val buttons = src("GroupScreens.kt")
        assertEquals(4, buttons.split(".alpha(enabledAlpha(enabled))").size - 1)
        assertFalse(".alpha(if (enabled) 1f else ButtonLook.DISABLED_ALPHA)" in buttons)
        // Error lines rise in.
        assertTrue("RevealOnEnter {" in buttons.substringAfter("internal fun ErrorLine("))
    }

    @Test
    fun the_meters_the_ring_and_the_pills_move() {
        val settled = src("Settled.kt").substringAfter("fun SettledCard(").substringBefore("\n}\n")
        assertTrue("SettledMeter(rememberFill(SettledMeters.fraction(b.basisPoints).toDouble()), big = true, dark)" in settled)
        assertTrue("b.basisPoints.toLong()," in settled)
        assertTrue("b.outstandingMinor," in settled)
        val hub = src("AccountHubScreen.kt")
        assertTrue("rememberFill(if (facts != null) RuneRing.share(done, total) else null, memory = \"you-ring\")" in hub)
        assertTrue("RuneRing(done, total, dark, Modifier.artTurn { RingTurn.degrees(it) }, sweep = ringFill)" in hub)
        assertTrue("CompletionRing(done, total, dark, sweep = ringFill)" in hub)
        val you = src("YouProgress.kt")
        assertTrue("val lit = RuneRing.litAt(sweep, done, total)" in you)
        assertTrue("sweepAngle = 360f * sweep.coerceIn(0.0, 1.0).toFloat()," in you)
        // One sliding pill per segmented control; no segment draws its own chosen fill any more.
        val spending = src("Spending.kt")
        assertEquals(2, spending.split("SlidingPill(places, ").size - 1)
        assertFalse(".then(if (chosen) Modifier.background(Tok.accent(dark), shape) else Modifier)" in spending)
        assertTrue("SlidingPill(\n            places, AppearanceMode.entries.indexOf(choice.chosen)" in hub)
        assertFalse("if (chosen) Modifier.shadow(ThemeArt.m(\"segmentShadowRadius\").dp, shape).background(Tok.surface(dark), shape)" in hub)
    }
}
