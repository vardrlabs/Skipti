package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-217 Task 7: every Android pushed screen draws its title and its back through [PushedBar] (SkiptiBar.kt), and
 * `ScreenHeader` is gone. The screens are looked at on a device (T7 look); these are the source guards that keep a
 * screen from drifting back to a title `Text` of its own, a "Back" word, or a nested page that system Back skips.
 * Each guard fails if it scanned nothing.
 */
class ScreenBarsTest {
    private val dir = java.io.File("src/main/java/com/vardrlabs/skipti")

    private fun mainFiles(): List<java.io.File> = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun src(name: String): String = java.io.File(dir, name).readText()

    /** The files whose screens moved onto the bar in Tasks 7 and 8 (the sheets: UsernameStep, AddExpenseScreen,
     *  CurrencyChoice; the person sheet is in RemovalFlow.kt). */
    private val converted = listOf(
        "AccountHubScreen.kt", "About.kt", "Profile.kt", "SignInScreen.kt", "AccountOwner.kt", "Phones.kt",
        "ExportScreen.kt", "ImportFlow.kt", "Invites.kt", "People.kt", "GroupScreens.kt", "GroupSettings.kt",
        "Push.kt", "ActivityFeed.kt", "RemovalFlow.kt", "UsernameStep.kt", "AddExpenseScreen.kt", "CurrencyChoice.kt",
    )

    @Test
    fun ScreenHeader_and_the_old_group_column_are_gone() {
        val files = mainFiles()
        assertTrue("scanned ${files.size} files", files.size > 40)
        for (f in files) {
            val t = f.readText()
            assertFalse("${f.name} still names ScreenHeader", t.contains("ScreenHeader"))
            assertFalse("${f.name} still has GroupScreenColumn", t.contains("GroupScreenColumn"))
            assertFalse("${f.name} still has the dead GroupListScreen", t.contains("GroupListScreen"))
        }
    }

    @Test
    fun no_screen_draws_the_word_Back() {
        val files = mainFiles()
        assertTrue(files.size > 40)
        for (f in files) {
            val t = f.readText()
            assertFalse("${f.name} draws Text(\"Back\"", t.contains("Text(\"Back\""))
            // D-217 Task 8: no exemption left (the add/edit form's "Back" is a Cancel pill on SheetBar now).
            assertFalse("${f.name} draws a Back TextButton", Regex("""TextButton\(onClick = onBack\)""").containsMatchIn(t))
            assertFalse("${f.name} says \"Back\" in a literal", Regex("""(Text\(|text = |if \([^)]*\) )"Back"""").containsMatchIn(t))
        }
    }

    /** `Type.title(` is the bar's alone (SkiptiBar.kt), and it has no default size to fall back on. */
    @Test
    fun Type_title_is_called_only_by_the_bar_and_has_no_default_size() {
        val callers = mainFiles().filter { it.name != "SkiptiBar.kt" && it.readText().contains("Type.title(") }
        assertEquals("callers outside SkiptiBar.kt: ${callers.map { it.name }}", 0, callers.size)
        val tokens = src("Tokens.kt")
        assertTrue(tokens.contains("fun title(dark: Boolean, size: Float) = TextStyle("))
        assertFalse(Regex("""fun title\(dark: Boolean, size: Float\s*=""").containsMatchIn(tokens))
    }

    /** Body names iOS also draws in the body (file, an anchor on the line): the only display copies left. */
    private val allowedBodyNames = listOf(
        // the "You're signed in" heading in the confirmation's body (iOS: TypeRole.display(28), AccountOwner.swift:132)
        "AccountOwner.kt" to "SignedInCopy.TITLE",
        // D-224 T6: the erased screen's heading in its body, laid out as the signed-in screen's (it has no bar)
        "AccountOwner.kt" to "EraseCopy.DONE_TITLE",
        // the request card's own title (RemovalFlow.swift draws the card title at display 22/700)
        "RemovalFlow.kt" to "titleStyle",
        // the invitation sentence and the joined line in the join page's body (iOS: display 28, Invites.swift:413, :424)
        "Invites.kt" to "InviteCopy.joined(",
        "Invites.kt" to "InviteCopy.invited(",
    )

    private val titleCopy = Regex("""Type\.display\(dark\)\.copy\(\s*fontSize = (28|24|22)\.sp""")
    private val defaultDisplay = Regex("""style = Type\.display\(dark\)\)""")

    @Test
    fun a_converted_screen_has_no_title_text_of_its_own() {
        var scanned = 0
        for (name in converted) {
            val lines = src(name).lines()
            scanned += lines.size
            lines.forEachIndexed { i, line ->
                if (titleCopy.containsMatchIn(line) || defaultDisplay.containsMatchIn(line)) {
                    // the copy may open on this line and the anchor sit a few lines either side
                    val around = lines.subList(maxOf(0, i - 3), minOf(lines.size, i + 3)).joinToString("\n")
                    val ok = allowedBodyNames.any { (f, anchor) -> f == name && around.contains(anchor) }
                    assertTrue("$name:${i + 1} draws a title of its own: ${line.trim()}", ok)
                }
            }
        }
        assertTrue("scanned $scanned lines", scanned > 5000)
        // Every allow-list entry is still real (a stale one would hide a new title).
        for ((f, anchor) in allowedBodyNames) assertTrue("$f no longer has $anchor", src(f).contains(anchor))
    }

    /** The body names the join page draws (iOS: the invitation sentence at display, Invites.swift) stay; they are not titles. */
    @Test
    fun the_join_pages_body_sentences_are_not_titles() {
        val t = src("Invites.kt")
        // The bar carries "Join a group"; the sentences are the offer, and are not InviteCopy.JOIN_TITLE.
        assertFalse(Regex("""Text\(InviteCopy\.JOIN_TITLE""").containsMatchIn(t))
    }

    /** (file, title expression as the bar is given it, how many bars with that title the file has). */
    private val pushedTitles = listOf(
        Triple("AccountHubScreen.kt", "PushedPage(if (section == null) YouCopy.TITLE else section.title, dark, onBack", 1),
        Triple("About.kt", "PushedPage(\"About\", dark, onBack", 1),
        Triple("About.kt", "PushedBar(\"Licences\", onBack", 1),
        Triple("About.kt", "PushedPage(entry?.first?.name ?: \"\", dark, onBack", 1),
        Triple("Profile.kt", "PushedPage(\"Profile\", dark, onBack", 1),
        Triple("SignInScreen.kt", "PushedBar(\"Sign in\", onBack", 1),
        Triple("AccountOwner.kt", "PushedBar(\"Sign in\", onBack = null", 1),
        Triple("AccountOwner.kt", "PushedPage(\"Sign in\", dark, onBack", 1),
        Triple("AccountOwner.kt", "PushedPage(EraseCopy.TITLE, dark, onBack", 1),
        Triple("Phones.kt", "PushedPage(PhonesCopy.TITLE, dark, onBack", 1),
        Triple("Phones.kt", "PushedPage(PhonesCopy.DELETE_TITLE, dark, onBack", 1),
        Triple("ExportScreen.kt", "PushedBar(\"Export\", onBack", 1),
        Triple("ImportFlow.kt", "PushedPage(ImportCopy.TITLE, dark, onBack", 1),
        Triple("ImportFlow.kt", "PushedPage(\"Review\", dark, onBack", 1),
        // D-217 final fix: JoinScreen's own page passes `exit` (no back once a join has started); Join paste's two keep onBack.
        Triple("Invites.kt", "PushedPage(InviteCopy.JOIN_TITLE, dark, onBack", 2),
        Triple("Invites.kt", "else PushedPage(InviteCopy.JOIN_TITLE, dark, exit", 1),
        Triple("Invites.kt", "PushedPage(InviteCopy.invite(member.name), dark, onBack", 1),
        Triple("People.kt", "PushedPage(PeopleCopy.findTitle(member.name), dark, onBack", 1),
        Triple("GroupScreens.kt", "PushedPage(\"People\", dark, onBack", 1),
        Triple("GroupSettings.kt", "PushedPage(RolesCopy.GROUP_SETTINGS_TITLE, dark, onBack = { if (!busy) onBack() }", 1),
        Triple("ActivityFeed.kt", "PushedPage(if (group == null) ActivityFeed.TITLE else ActivityFeed.GROUP_TITLE, dark, onBack", 1),
        Triple("RemovalFlow.kt", "PushedBar(title, onBack, scroll = scroll, backdrop = backdrop)", 1),
    )

    @Test
    fun every_pushed_screen_takes_its_ios_title_through_the_bar() {
        assertTrue(pushedTitles.size > 20)
        for ((file, call, n) in pushedTitles) {
            val count = src(file).split(call).size - 1
            assertEquals("$file: $call", n, count)
        }
    }

    /** (file, anchor) for each sheet iOS presents and Android now draws on the sheet bar (D-217 Task 8, rows 5 to 7, 28,
     *  31, 41 to 44). `SheetPage` is the bar's page (SheetBar, Cancel, the inline or large title, the pinned action). */
    private val sheets = listOf(
        "AddExpenseScreen.kt" to "SheetPage(\n        title = if (editingId != null) \"Edit expense\" else \"Add expense\",\n        dark = dark,\n        onCancel = onBack,",
        "GroupScreens.kt" to "SheetPage(\"New group\", dark, onCancel = onBack",
        "GroupScreens.kt" to "SheetPage(\n        \"Your name\", dark, onCancel = onBack",
        // D-224 T6: the add-person page sits in a Box with its own toast host, one indent deeper.
        "People.kt" to "SheetPage(\n            AddPersonCopy.TITLE, dark, onCancel = onBack",
        "RemovalFlow.kt" to "SheetBar(name, onCancel = onCancel, scroll = scroll, onSurface = true, underStatusBar = false)",
        "UsernameStep.kt" to "SheetPage(\n        title, dark, onCancel = onCancel",
    )

    @Test
    fun each_listed_sheet_takes_the_sheet_bar() {
        assertEquals(6, sheets.size)
        for ((file, anchor) in sheets) {
            val t = src(file)
            assertEquals("$file: $anchor", 1, t.split(anchor).size - 1)
        }
        // The page takes the bar: Cancel pill, the title, the pinned action.
        val bar = src("SkiptiBar.kt")
        assertTrue(bar.contains("SheetBar(title, onCancel, scroll = scroll, cancelLabel = cancelLabel, largeTitleBottom"))
        assertTrue(bar.contains("if (actions != null) FormActionBar(actions)"))
        // The three stopgaps are off the pushed page for good, and a sheet is on no PushedPage.
        assertFalse(src("GroupScreens.kt").contains("PushedPage(\"New group\""))
        assertFalse(src("GroupScreens.kt").contains("PushedPage(\"Your name\""))
        assertFalse(src("People.kt").contains("PushedPage(AddPersonCopy.TITLE"))
        // Pick your username and Change username share one screen: its Cancel text button and 28/700 title are gone.
        val username = src("UsernameStep.kt")
        assertFalse(username.contains("TextButton(onClick = onCancel)"))
        assertFalse(username.contains("fontSize = 28.sp, fontWeight = FontWeight(700)"))
        // ...and its two users (Pick your username, Change username) both go through it.
        assertEquals(2, Regex("""^\s+UsernameScreen\(""", RegexOption.MULTILINE).findAll(username).count())
    }

    /** iOS: the add and edit form's bar says Cancel on both (HomeShell.swift:179-181, ExpenseListView.swift:198,
     *  ExpenseDetailView.swift:57); its title is LARGE under the bar and Save is the pinned primary button. */
    @Test
    fun the_add_and_edit_form_says_cancel_on_both_and_has_a_large_title_and_a_pinned_save() {
        val t = src("AddExpenseScreen.kt")
        assertFalse("\"Back\" in the form", t.contains("\"Back\""))
        assertFalse("a TextButton in the form", t.contains("TextButton("))
        assertFalse("the old centred 17 title", t.contains("Type.label(dark, 17)"))
        assertTrue(t.contains("largeTitle = true"))
        assertTrue(t.contains("\"Edit expense\"") && t.contains("\"Add expense\""))
        // Cancel is the bar's default pill label, one word on new and edit alike.
        assertEquals("Cancel", BarDecisions.CANCEL_LABEL)
        assertTrue(src("SkiptiBar.kt").contains("cancelLabel: String = BarDecisions.CANCEL_LABEL"))
        // Save is the pinned action (iOS formActionBar), saving through the one PrimaryAction.
        val actions = t.indexOf("actions = {")
        val save = t.indexOf("PrimaryAction(", actions)
        val saveLabel = t.indexOf("editingId != null -> \"Save changes\"", actions)
        assertTrue("Save is not inside actions", actions in 0 until save && save < saveLabel)
        // Both routes hand the form a way out (a Cancel), so the bar is drawn.
        val root = src("AppRoot.kt")
        assertEquals(2, Regex("""AddExpenseScreen\(\s*vaultPath = vaultPath,[\s\S]{0,300}?onBack = """).findAll(root).count())
        // Only Add and Edit expense have a large sheet title.
        val largeUsers = mainFiles().filter { it.name != "SkiptiBar.kt" && it.readText().contains("largeTitle = true") }
        assertEquals(listOf("AddExpenseScreen.kt"), largeUsers.map { it.name })
    }

    /** iOS: Add a person's leading pill reads "Done" (People.swift:907, `.cancellationAction`); it only closes. */
    @Test
    fun add_a_person_has_done_in_the_leading_corner() {
        assertEquals("Done", BarDecisions.DONE_LABEL)
        val people = src("People.kt")
        assertTrue(people.contains("side = BarDecisions.PAGE_SIDE_WIDE.dp, cancelLabel = BarDecisions.DONE_LABEL"))
        // The bar draws that label at the START, where Cancel is, and has no Done at the end for this sheet.
        assertTrue(src("SkiptiBar.kt").contains("start = { BarPill(cancelLabel, dark, true, onSurface, onCancel) },"))
        assertEquals(1, people.split("cancelLabel = BarDecisions.DONE_LABEL").size - 1)
    }

    /** iOS: the All currencies list is pushed inside the sheet, with the round back button and the inline title. */
    @Test
    fun the_currency_list_has_a_pushed_bar_not_a_title_and_a_close_button() {
        val t = src("CurrencyChoice.kt")
        // D-220 (L-D217-6): its window runs under the status bar now, so the bar reaches up as a page's does.
        // D-225: the shared PushedWindow (ListRows.kt) draws that bar with the list's title.
        assertTrue(t.contains("PushedWindow(CurrencyCopy.ALL_CURRENCIES, dark, onClose)"))
        assertTrue(src("ListRows.kt").contains("PushedBar(title, onBack = onClose, scroll = scroll, backdrop = backdrop)"))
        assertFalse(t.contains("Type.display(dark).copy(fontSize = 22.sp)"))
        assertFalse("a Close button", t.contains("CurrencyCopy.CLOSE"))
        assertFalse(t.contains("TextButton("))
    }

    /** The person sheet: the name is its bar title and Cancel closes it (RemovalFlow.swift:742-743, :927). */
    @Test
    fun the_person_sheet_has_a_cancel_that_closes_it() {
        // D-217 final fix: the wiring, end to end: the group page hands the sheet its close, and the sheet's own
        // signature takes it and gives it to its bar's Cancel.
        val sheet = src("GroupScreens.kt")
        val call = sheet.substringAfter("PersonSheetContent(").substringBefore("\n                )\n")
        assertTrue("the call closes the sheet", call.contains("onCancel = { sheetId = null },"))
        val content = src("RemovalFlow.kt").substringAfter("fun PersonSheetContent(")
        val signature = content.substringBefore(") {")
        assertTrue("the sheet takes it", signature.contains("    onCancel: () -> Unit,\n"))
        val body = content.substringBefore("\n}\n")
        assertTrue("its bar's Cancel is it", body.contains("SheetBar(name, onCancel = onCancel, scroll = scroll, onSurface = true, underStatusBar = false)"))
    }

    /** D-225 follow-up (R4, the re-look's pairs role-nd and role-vl): the person sheet is laid out as iOS's Form: the
     *  person in a header card 43 below Cancel (the avatar touched Cancel), cards 33.67 apart, the Role label between
     *  its cards as iOS's section header, the rows a two-line Form row tall (67.33) with a hairline between rows, inset
     *  16 as iOS's separators. Measured on iOS 27 (pt) beside API 34 (dp). */
    @Test
    fun the_person_sheet_is_laid_out_as_ios_form() {
        // The header card: the 48 avatar in iOS's 74.67 row. A two-line row: iOS's pitch 67.33, from Android's measured
        // 57.53 at 10 a side with no line gap. Re-derived from the measurements, not read back from the code.
        assertEquals(74.67f, 48f + 2 * PersonSheetLayout.HEADER_PAD_V, 0.05f)
        assertEquals(67.33f, 57.53f - 2 * 10f + PersonSheetLayout.ROW_LINE_GAP + 2 * PersonSheetLayout.ROW_PAD_V, 0.05f)
        assertEquals(43f, PersonSheetLayout.TOP, 0f)
        assertEquals(33.67f, PersonSheetLayout.CARD_GAP, 0f)
        assertEquals(16f, PersonSheetLayout.INSET, 0f)
        // The label's box sits so its ink is iOS's 29.3 under the card above and 13.3 over its card (the face's ink is
        // 3.24 dp under the box top and 3.62 over its bottom).
        assertEquals(29.33f, PersonSheetLayout.LABEL_ABOVE + 3.24f, 0.05f)
        assertEquals(13.33f, PersonSheetLayout.LABEL_BELOW + 3.62f, 0.05f)

        val flow = src("RemovalFlow.kt")
        val content = flow.substringAfter("fun PersonSheetContent(").substringBefore("\n}\n")
        val body = content.substringAfter("SheetBar(name, onCancel = onCancel, scroll = scroll, onSurface = true, underStatusBar = false)")
        assertFalse("no flat 12 between the pieces", "verticalArrangement = Arrangement.spacedBy(12.dp)" in body)
        assertTrue("43 under Cancel first", Regex("""\.padding\(bottom = 16\.dp\),\s*\) \{\s*Spacer\(Modifier\.height\(PersonSheetLayout\.TOP\.dp\)\)""").containsMatchIn(body))
        val header = body.substringAfter("Spacer(Modifier.height(PersonSheetLayout.TOP.dp))").substringBefore("PersonAvatar(name, dark, 48, preview)")
        assertTrue("the person in a card", "RowsCard(dark, listOf {" in header)
        assertTrue("padded as iOS's header row",
            "Modifier.padding(horizontal = PersonSheetLayout.INSET.dp, vertical = PersonSheetLayout.HEADER_PAD_V.dp)" in header)
        assertTrue("cards apart by iOS's gap", "Spacer(Modifier.height(PersonSheetLayout.CARD_GAP.dp))" in body)
        val role = body.substringAfter("if (row.offer.makeAdmin || row.offer.removeAdmin || row.offer.makeOwner) {")
        assertTrue("the label placed as iOS's header",
            Regex("""Spacer\(Modifier\.height\(PersonSheetLayout\.LABEL_ABOVE\.dp\)\)\s*SectionLabel\(RolesCopy\.ROLE_SECTION, dark\)\s*Spacer\(Modifier\.height\(PersonSheetLayout\.LABEL_BELOW\.dp\)\)""").containsMatchIn(role))
        // The card draws a hairline between rows, never before the first; the row is iOS's Form row.
        val card = flow.substringAfter("private fun RowsCard(").substringBefore("\n}\n")
        assertTrue("between rows only", "if (i > 0) Box(Modifier.padding(horizontal = PersonSheetLayout.INSET.dp).fillMaxWidth().height(hairline).background(Tok.hairline(dark)))" in card)
        val row = flow.substringAfter("private fun ActionRow(").substringBefore("\n}\n")
        assertTrue(".padding(horizontal = PersonSheetLayout.INSET.dp, vertical = PersonSheetLayout.ROW_PAD_V.dp)" in row)
        assertTrue("Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PersonSheetLayout.ROW_LINE_GAP.dp))" in row)
    }

    /** D-217 final fix (I1): iOS presents Join as a sheet (Cancel pill) from an invitation and from an opened link, and
     *  pushes it inside Join paste. Android draws the same: the two sheet entries say `asSheet = true`, the nested one
     *  `false`. Once a join has started neither shows a Cancel or a back (iOS hides both), and system Back is Done. */
    @Test
    fun join_is_a_sheet_where_ios_presents_one_and_locks_once_a_join_starts() {
        val root = src("AppRoot.kt")
        assertEquals("the opened link", 1, root.split("JoinScreen(vaultPath, openedInvite, onJoined = { onInviteHandled() }, onBack = onInviteHandled, asSheet = true)").size - 1)
        assertEquals("the invitation", 1, root.split("}, onBack = { route = null }, asSheet = true)").size - 1)
        assertEquals("every JoinScreen call says which", 2, root.split("JoinScreen(").size - 1)
        val invites = src("Invites.kt")
        assertEquals("Join paste pushes it", 1, invites.split("JoinScreen(vaultPath, it, onJoined, onBack = { link = null }, asSheet = false)").size - 1)
        assertTrue(invites.contains("fun JoinScreen(vaultPath: String, link: String, onJoined: (String) -> Unit, onBack: () -> Unit, asSheet: Boolean) {"))
        // The two bars, one decision: no Cancel or back once locked.
        assertTrue(invites.contains("val exit = if (JoinBar.locked(joining, joined)) null else onBack"))
        assertTrue(invites.contains("if (asSheet) SheetPage(InviteCopy.JOIN_TITLE, dark, onCancel = exit, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp, content = body)"))
        // System Back once locked: Done when joined, nothing while joining.
        assertTrue(invites.contains("BackHandler(enabled = JoinBar.locked(joining, joined)) { joined?.let(onJoined) }"))
        assertFalse(JoinBar.locked(joining = false, joined = null))
        assertTrue(JoinBar.locked(joining = true, joined = null))
        assertTrue(JoinBar.locked(joining = false, joined = "g1"))
        assertTrue(JoinBar.locked(joining = true, joined = "g1"))
        // The opened link sits outside HomeShell: its own BackHandler closes it as the bar's Cancel does, just before it.
        val h = root.indexOf("BackHandler { onInviteHandled() }")
        val j = root.indexOf("JoinScreen(vaultPath, openedInvite", maxOf(h, 0))
        assertTrue("the opened link's BackHandler", h >= 0 && j > h && j - h < 200)
        assertEquals(1, root.split("BackHandler { onInviteHandled() }").size - 1)
    }

    /** D-217 final fix (I7): the sheet roots iOS presents that Android had drawn as pushed pages. A request card's
     *  Review is iOS's RemovalSheet (Cancel at its root; its later steps push, so they keep the back); the two pages
     *  only a tapped push opens (the invitations, an expense) are sheets with "Close" (SkiptiApp.swift). */
    @Test
    fun the_review_and_the_push_only_pages_are_sheets_as_on_ios() {
        assertEquals("Close", BarDecisions.CLOSE_LABEL)
        val ios = java.io.File("../../ios/Skipti/App/SkiptiApp.swift").readText()
        assertTrue(ios.contains("Button(\"Close\") { push.opened = nil }.barButtonText().barTint()"))
        assertTrue(ios.contains("Button(\"Close\") { push.openedInvitations = false }.barButtonText().barTint()"))
        assertTrue(java.io.File("../../ios/Skipti/App/RemovalFlow.swift").readText()
            .contains("ToolbarItem(placement: .cancellationAction) { Button(RolesCopy.cancel) { onClose() }.barButtonText().barTint() }"))
        // The removal flow: FlowScreen draws the sheet bar when given a cancel; only the review's root is.
        val flow = src("RemovalFlow.kt")
        assertTrue(flow.contains("    cancel: (() -> Unit)? = null,\n"))
        assertTrue(flow.contains("if (cancel != null) SheetBar(title, onCancel = cancel, scroll = scroll, backdrop = backdrop) else PushedBar(title, onBack, scroll = scroll, backdrop = backdrop)"))
        assertEquals("the review step and its loading state", 2, flow.split("cancel = if (route.review) ").size - 1 + flow.split("dark, RolesCopy.balanceTitle(name), onBack = ::back, cancel = ::back,").size - 1)
        assertEquals(1, flow.split("dark, RolesCopy.balanceTitle(name), onBack = ::back, cancel = ::back,").size - 1)
        assertEquals("no other step has a cancel", 2, flow.split("cancel = ").size - 1)
        // The two push-only pages.
        assertTrue(src("People.kt").contains("SheetPage(PeopleCopy.INVITATIONS, dark, onCancel = onBack, spacing = 12.dp, cancelLabel = BarDecisions.CLOSE_LABEL)"))
        val push = src("Push.kt")
        assertTrue(push.contains("SheetPage(\"\", dark, onCancel = onBack, spacing = 12.dp, cancelLabel = BarDecisions.CLOSE_LABEL)"))
        assertTrue(push.contains("ExpenseDetailScreen(vaultPath, target.expense, onBack, onEdit, onDeleted, closeLabel = BarDecisions.CLOSE_LABEL)"))
        val detail = src("ExpenseDetailScreen.kt")
        assertTrue(detail.contains("if (closeLabel != null) SheetBar(ExpenseDetailUi.title(expense?.note), onCancel = onBack, scroll = scroll, cancelLabel = closeLabel, backdrop = backdrop)"))
        // The invitations and the waiting expense are opened only by a tapped push.
        val root = src("AppRoot.kt")
        assertEquals(1, root.split("route = \"invitations\"").size - 1)
        assertEquals(1, root.split("route = \"pushed/").size - 1)
    }

    /** The pinned action bar is iOS's `formActionBar` on every screen that has one: 20 pt sides, 8 above and below. */
    @Test
    fun a_pinned_action_bar_is_the_form_action_bar() {
        val bar = src("SkiptiBar.kt")
        assertTrue(bar.contains("Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)"))
        assertTrue(src("RemovalFlow.kt").contains("FormActionBar { actions() }"))
        assertFalse(src("RemovalFlow.kt").contains("start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp"))
    }

    /** The sheets whose iOS twin draws at `.padding(20)` (People.swift:902; UsernameStep.swift:256, :369) say so; the
     *  Form-based ones (New group, Your name, Add expense) keep the default. */
    @Test
    fun a_sheet_has_the_side_padding_its_ios_twin_has() {
        val wide = "side = BarDecisions.PAGE_SIDE_WIDE.dp"
        val users = mainFiles().filter { it.name != "SkiptiBar.kt" && it.readText().contains("SheetPage(") }
        assertEquals(listOf("AddExpenseScreen.kt", "GroupScreens.kt", "Invites.kt", "People.kt", "Push.kt", "UsernameStep.kt"), users.map { it.name }.sorted())
        assertTrue(src("Invites.kt").contains("SheetPage(InviteCopy.JOIN_TITLE, dark, onCancel = exit, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp"))
        assertTrue(src("People.kt").contains(wide))
        assertTrue(src("UsernameStep.kt").contains(wide))
        assertFalse(src("AddExpenseScreen.kt").contains(wide))
        assertFalse(src("GroupScreens.kt").contains("PAGE_SIDE_WIDE"))
    }

    /** iOS's pushed pages are not uniform: these twins scroll a VStack at `.padding(20)` (About.swift:266, :310, :394;
     *  AccountHubScreen.swift:230, :323; Profile.swift:370; Invites.swift:303, :341, :433), every other pushed page is
     *  16 (Phones, Activity, Find, Invitations, the removal flow, Export, the waiting expense) or a system Form. */
    private val widePages = listOf(
        "About.kt" to "PushedPage(\"About\"",
        "About.kt" to "PushedPage(entry?.first?.name",
        "AccountHubScreen.kt" to "PushedPage(if (section == null) YouCopy.TITLE",
        "Profile.kt" to "PushedPage(\"Profile\"",
        // D-225 T5: the Erase page, pushed from Security (a You section, AccountHubScreen.swift's padding(20)).
        "AccountOwner.kt" to "PushedPage(EraseCopy.TITLE",
        "Invites.kt" to "PushedPage(InviteCopy.invite(member.name)",
        "Invites.kt" to "PushedPage(InviteCopy.JOIN_TITLE",
    )

    @Test
    fun a_pushed_page_has_the_side_padding_its_ios_twin_has() {
        val wide = "side = BarDecisions.PAGE_SIDE_WIDE.dp"
        var calls = 0
        var wideCalls = 0
        for (f in mainFiles()) {
            if (f.name == "SkiptiBar.kt") continue
            for (line in f.readLines()) {
                if (!line.contains("PushedPage(")) continue
                calls++
                val expectWide = widePages.any { (file, anchor) -> file == f.name && line.contains(anchor) }
                if (expectWide) wideCalls++
                assertEquals("${f.name}: ${line.trim()}", expectWide, line.contains(wide))
            }
        }
        assertTrue("PushedPage calls found: $calls", calls > 15)
        // About, a licence, You, Profile, Erase (D-225), Invite and the three Join pages.
        assertEquals(9, wideCalls)
        // Licences' list is the same 20 on both sides, not 16.
        assertTrue(src("About.kt").contains("contentPadding = PaddingValues(start = BarDecisions.PAGE_SIDE_WIDE.dp, end = BarDecisions.PAGE_SIDE_WIDE.dp"))
        val bar = src("SkiptiBar.kt")
        assertTrue(bar.contains("const val PAGE_SIDE = 16"))
        assertTrue(bar.contains("const val PAGE_SIDE_WIDE = 20"))
        assertTrue(bar.contains("side: Dp = BarDecisions.PAGE_SIDE.dp"))
        // D-217 look fix 1: the top is its own parameter (a ScrollView's by default), not the tab root's constant.
        assertTrue(bar.contains(".padding(start = side, end = side, top = top, bottom = 16.dp)"))
    }

    @Test
    fun the_sign_in_form_has_an_on_screen_back_that_goes_where_system_back_goes() {
        val form = src("SignInScreen.kt")
        assertTrue(form.contains("fun SignInScreen(vaultPath: String, onBack: () -> Unit, onSignedIn:"))
        assertFalse("a 40 sp title", form.contains("Text(\"Sign in\", style = Type.display(dark))"))
        val root = src("AppRoot.kt")
        // the bar's button and BackHandler share one parent
        assertTrue(root.contains("SignInScreen(vaultPath, onBack = { route = HomeRoutes.parent(shown) })"))
        // the confirmation keeps its title and has no back button
        assertTrue(src("AccountOwner.kt").contains("PushedBar(\"Sign in\", onBack = null"))
    }

    @Test
    fun the_activity_title_is_the_bars_not_a_40_sp_text() {
        val t = src("ActivityFeed.kt")
        assertFalse(Regex("""Text\(\s*if \(group == null\) ActivityFeed\.TITLE""").containsMatchIn(t))
        assertTrue(t.contains("PushedPage(if (group == null) ActivityFeed.TITLE else ActivityFeed.GROUP_TITLE"))
    }

    /** (file, the BackHandler line, the nested screen it covers): system Back goes up one level (oddity 5), and the
     *  handler sits just before the branch that draws the nested screen, so it is enabled only while it shows. */
    private val nested = listOf(
        Triple("GroupScreens.kt", "BackHandler { inviting = null }", "InviteScreen(vaultPath, group, invitee"),
        Triple("GroupScreens.kt", "BackHandler { adding = false; loads++ }", "AddPersonScreen("),
        Triple("Invites.kt", "BackHandler { finding = false }", "FindPersonScreen("),
        Triple("Invites.kt", "BackHandler { link = null }", "JoinScreen(vaultPath, it, onJoined"),
        Triple("ImportFlow.kt", "BackHandler { job = null }", "ImportReviewScreen(current"),
    )

    @Test
    fun every_nested_push_has_its_own_BackHandler() {
        for ((file, handler, screen) in nested) {
            val t = src(file)
            val h = t.indexOf(handler)
            val s = t.indexOf(screen, maxOf(h, 0))
            assertTrue("$file lacks $handler", h >= 0)
            assertTrue("$file: $screen not found after $handler", s > h)
            assertTrue("$file: $handler is ${s - h} chars from $screen", s - h < 260)
            assertEquals("$file: one $handler", 1, t.split(handler).size - 1)
        }
        assertEquals(5, nested.size)
    }

    @Test
    fun a_lazy_lists_ground_shows_once_it_has_moved() {
        assertEquals(0, BarDecisions.lazyScrollPx(0, 0))
        assertEquals(37, BarDecisions.lazyScrollPx(0, 37))
        assertEquals(1, BarDecisions.lazyScrollPx(3, 0))
        assertEquals(40, BarDecisions.lazyScrollPx(3, 40))
        assertFalse(BarDecisions.barGroundShown(BarDecisions.lazyScrollPx(0, 0)))
        assertTrue(BarDecisions.barGroundShown(BarDecisions.lazyScrollPx(1, 0)))
    }

    /** The bar's back is optional: a null draws none (iOS's signed-in confirmation). */
    @Test
    fun a_null_back_draws_no_button() {
        val bar = src("SkiptiBar.kt")
        assertTrue(bar.contains("fun PushedBar(\n    title: String,\n    onBack: (() -> Unit)?,"))
        assertTrue(bar.contains("start = if (onBack != null) ({ BackButton(dark, onBack) }) else null,"))
        // D-217 final fix: PushedPage hands its nullable back straight to the bar.
        assertTrue(bar.contains("fun PushedPage(\n    title: String,\n    dark: Boolean,\n    onBack: (() -> Unit)?,"))
        assertTrue(bar.substringAfter("fun PushedPage(").substringBefore("\n}\n").contains("PushedBar(title, onBack, trailing, scroll, backdrop = backdrop)"))
    }

    /** The scrolling pages hand their scroll to the bar, so its ground appears once scrolled (as iOS). */
    @Test
    fun the_scrolling_pages_give_the_bar_their_scroll() {
        val bar = src("SkiptiBar.kt")
        // D-220: with the page's backdrop.
        assertTrue(bar.contains("PushedBar(title, onBack, trailing, scroll, backdrop = backdrop)"))
        assertTrue(src("About.kt").contains("lazy = list"))
        assertTrue(src("ExportScreen.kt").contains("PushedBar(\"Export\", onBack, scroll = scroll, backdrop = backdrop)"))
        assertTrue(src("RemovalFlow.kt").contains("verticalScroll(scroll)"))
    }
}
