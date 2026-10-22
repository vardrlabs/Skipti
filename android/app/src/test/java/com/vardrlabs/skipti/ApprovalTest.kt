package com.vardrlabs.skipti

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * D-254 (decisions 228, 229): the approval gate on the phone, the twin of iOS's ApprovalTests, test for test. The
 * decision from (what the phone knew, what the server said), the words (read against iOS's `ApprovalCopy` in
 * Approval.swift), the account hub's four states, the gates (an account not approved pushes, listens, registers and calls
 * nothing; the calls that let a person leave still go), `details.approval`'s mapping, the vault's local-only copy cleared
 * at sign-out, the pending deletion of an account with no profile, and the emulator helper kept to debug builds and the
 * emulators. Goldens are written out by hand, never read back from the implementation.
 */
class ApprovalTest {
    private val uid = "uid-approval-test"
    private val dir = Files.createTempDirectory("approval")

    private fun tempStore(owner: String? = null): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = dir.resolve("vault-${System.nanoTime()}.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        if (owner != null) store.rawKvSet(Account.OWNER_KEY, owner.toByteArray(Charsets.UTF_8))
        return store
    }

    private fun main(name: String) = File("src/main/java/com/vardrlabs/skipti/$name").readText()
    private fun swift(name: String) = File("../../ios/Skipti/App/$name").readText()
    private fun mainSources(): List<Pair<String, String>> =
        File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!.sortedBy { it.name }
            .map { it.name to it.readText() }

    /** The text after [from], up to the first [to] after it. */
    private fun slice(text: String, from: String, to: String): String {
        assertTrue("anchor $from", from in text)
        return text.substringAfter(from).substringBefore(to)
    }

    /** Each needle appears in [text], in this order. */
    private fun inOrder(text: String, vararg needles: String) {
        var at = 0
        for (needle in needles) {
            val found = text.indexOf(needle, at)
            if (found < 0) fail("missing, or out of order: $needle")
            at = found + needle.length
        }
    }

    // ---- The decision ----

    /** Every (cached, server) pair, the table written out by hand: the toast only after the phone showed waiting or
     *  denied; approval taken away stops the cloud; no answer changes nothing. */
    @Test
    fun the_decision_for_every_cached_and_server_pair() {
        val u = ApprovalState.UNKNOWN
        val p = ApprovalState.PENDING
        val a = ApprovalState.APPROVED
        val d = ApprovalState.DENIED
        val table = listOf(
            Triple(u, u, ApprovalStep.Nothing), Triple(u, p, ApprovalStep.Keep(p)),
            Triple(u, a, ApprovalStep.Approve(toast = false)), Triple(u, d, ApprovalStep.Keep(d)),
            Triple(p, u, ApprovalStep.Nothing), Triple(p, p, ApprovalStep.Nothing),
            Triple(p, a, ApprovalStep.Approve(toast = true)), Triple(p, d, ApprovalStep.Keep(d)),
            Triple(a, u, ApprovalStep.Nothing), Triple(a, p, ApprovalStep.Withdraw(p)),
            Triple(a, a, ApprovalStep.Nothing), Triple(a, d, ApprovalStep.Withdraw(d)),
            Triple(d, u, ApprovalStep.Nothing), Triple(d, p, ApprovalStep.Keep(p)),
            Triple(d, a, ApprovalStep.Approve(toast = true)), Triple(d, d, ApprovalStep.Nothing),
        )
        assertEquals("every pair", ApprovalState.entries.size * ApprovalState.entries.size, table.size)
        for ((cached, server, want) in table) assertEquals("$cached then $server", want, Approval.decide(cached, server))
    }

    /** The server's words: the three states, and no document or `none` (a deletion marker alone) as pending, as the
     *  server's own gate reads them. Nothing unknown is ever approved. */
    @Test
    fun the_servers_words_read_as_the_server_reads_them() {
        assertEquals(ApprovalState.APPROVED, ApprovalState.server("approved"))
        assertEquals(ApprovalState.DENIED, ApprovalState.server("denied"))
        assertEquals(ApprovalState.PENDING, ApprovalState.server("pending"))
        assertEquals(ApprovalState.PENDING, ApprovalState.server("none"))
        assertEquals(ApprovalState.PENDING, ApprovalState.server(null))
        assertEquals("closed, never approved by accident", ApprovalState.PENDING, ApprovalState.server("APPROVED"))
    }

    // ---- The words ----

    /** The approved words (decision 233), letter for letter, and no em or en dash in any of them. */
    @Test
    fun the_copy_table() {
        assertEquals("Waiting for approval", ApprovalCopy.ACCOUNT_WAITING)
        assertEquals("Not approved", ApprovalCopy.ACCOUNT_DENIED)
        assertEquals("Waiting", ApprovalCopy.SYNC_WAITING_VALUE)
        assertEquals(
            "SKIPTI is in early testing, so new accounts are approved by hand. Your ledger on this phone works as usual. Sync and shared groups start once your account is approved.",
            ApprovalCopy.SYNC_WAITING_BODY,
        )
        assertEquals("Off", ApprovalCopy.SYNC_DENIED_VALUE)
        assertEquals(
            "This account was not approved for sync. Your ledger on this phone works as usual and stays on this phone.",
            ApprovalCopy.SYNC_DENIED_BODY,
        )
        assertEquals(
            "You can join Goa trip once your account is approved. The link works for 7 days, so open it again then.",
            ApprovalCopy.joinWaiting("Goa trip"),
        )
        assertEquals(
            "You can join this group once your account is approved. The link works for 7 days, so open it again then.",
            ApprovalCopy.joinWaiting(null),
        )
        assertEquals("a blank name is no name", ApprovalCopy.joinWaiting(null), ApprovalCopy.joinWaiting("  "))
        assertEquals("This account was not approved, so it cannot join groups.", ApprovalCopy.JOIN_DENIED_BODY)
        assertEquals("Inviting people starts once your account is approved.", ApprovalCopy.INVITE_WAITING)
        assertEquals("This account was not approved, so it cannot invite people.", ApprovalCopy.INVITE_DENIED)
        assertEquals("Your account is approved. Syncing now.", ApprovalCopy.APPROVED_TOAST)
        assertEquals("This account is waiting for approval.", ApprovalCopy.CALL_WAITING)
        assertEquals("This account was not approved.", ApprovalCopy.CALL_DENIED)
        assertEquals("Done", ApprovalCopy.DONE)
        for (line in copyFields().values) assertFalse(line, '—' in line || '–' in line)
        // Where each state shows what: approved and unknown keep today's screens.
        assertEquals("Waiting for approval", ApprovalCopy.accountSummary(ApprovalState.PENDING))
        assertEquals("Not approved", ApprovalCopy.accountSummary(ApprovalState.DENIED))
        assertNull(ApprovalCopy.accountSummary(ApprovalState.APPROVED))
        assertNull(ApprovalCopy.accountSummary(ApprovalState.UNKNOWN))
        assertEquals(ApprovalCopy.INVITE_WAITING, ApprovalCopy.invite(ApprovalState.PENDING))
        assertEquals(ApprovalCopy.INVITE_DENIED, ApprovalCopy.invite(ApprovalState.DENIED))
        assertNull(ApprovalCopy.invite(ApprovalState.APPROVED))
        assertNull(ApprovalCopy.invite(ApprovalState.UNKNOWN))
        assertEquals("Waiting for approval" to ApprovalCopy.joinWaiting("Goa trip"), ApprovalCopy.join(ApprovalState.PENDING, "Goa trip"))
        assertEquals("Not approved" to ApprovalCopy.JOIN_DENIED_BODY, ApprovalCopy.join(ApprovalState.DENIED, "Goa trip"))
        assertNull(ApprovalCopy.join(ApprovalState.APPROVED, null))
        assertNull(ApprovalCopy.join(ApprovalState.UNKNOWN, null))
    }

    /** Every `const val` of [ApprovalCopy], by name. */
    private fun copyFields(): Map<String, String> = ApprovalCopy::class.java.fields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .associate { it.name to it.get(null) as String }

    /** Twin parity: iOS's `ApprovalCopy` table, read out of Approval.swift (one `static let name = "..."` a line), is this
     *  one, name for name (camel case to upper snake) and letter for letter. */
    @Test
    fun the_copy_table_is_ios_own() {
        val table = slice(swift("Approval.swift"), "enum ApprovalCopy {", "\n    static func ")
        val line = Regex("""^    static let ([a-zA-Z]+) = "([^"\\]*)"$""")
        val ios = table.lines().filter { it.startsWith("    static let ") }.associate { l ->
            val m = line.matchEntire(l) ?: error("not one literal on one line: $l")
            m.groupValues[1].replace(Regex("([A-Z])"), "_$1").uppercase() to m.groupValues[2]
        }
        assertEquals(15, ios.size)
        assertEquals(ios, copyFields())
        // Not vacuous: one changed letter is seen.
        assertNotEquals(ios + ("DONE" to "Done."), copyFields())
    }

    // ---- The account hub ----

    /** The four states: no account (whatever the approval says), active (approved, or not heard yet), waiting and not
     *  approved. Sign out and Delete account are there in every signed-in state. */
    @Test
    fun the_account_hub_has_four_states() {
        for (approval in ApprovalState.entries) {
            assertEquals(AccountState.NO_ACCOUNT, accountHubModel(signedIn = false, syncOn = true, approval = approval).state)
        }
        assertEquals(AccountState.ACTIVE, accountHubModel(true, true, ApprovalState.APPROVED).state)
        assertEquals(AccountState.ACTIVE, accountHubModel(true, true, ApprovalState.UNKNOWN).state)
        assertEquals(AccountState.WAITING, accountHubModel(true, true, ApprovalState.PENDING).state)
        assertEquals(AccountState.NOT_APPROVED, accountHubModel(true, true, ApprovalState.DENIED).state)
        for (approval in ApprovalState.entries) {
            val model = accountHubModel(signedIn = true, syncOn = false, approval = approval)
            assertTrue("$approval", model.showsAccountControls)
            assertFalse(model.syncOn)
            val items = SettingsMap.items(SettingsSection.PROFILE_ACCOUNT, model.showsAccountControls) +
                SettingsMap.items(SettingsSection.SECURITY, model.showsAccountControls)
            assertTrue("leaving never waits ($approval)", SettingItem.SIGN_OUT in items && SettingItem.DELETE_ACCOUNT in items)
        }
        // The page draws from the model's controls, never from ACTIVE alone (a waiting account keeps Sign out).
        val hub = main("AccountHubScreen.kt")
        assertTrue("val signedIn = model.showsAccountControls" in hub)
        assertFalse("val signedIn = model.state == AccountState.ACTIVE" in hub)
        // Approval arriving while the page is open reads it again.
        assertTrue("LaunchedEffect(RemoteSignOut.signOuts, ProfileState.revision, section, Approval.state) { reload() }" in hub)
    }

    /** You's Account row: the waiting or not approved words stand in for "Google · 1 phone"; otherwise as today. */
    @Test
    fun the_account_row_says_waiting_or_not_approved() {
        fun parts(approval: ApprovalState, signedIn: Boolean = true) = YouRows.parts(
            SettingsSection.PROFILE_ACCOUNT,
            YouRowFacts(signedIn, SignInMethod.Google.rowLabel, 1, AppTheme.AURORA, AppearanceMode.DARK, LockTimeout.ONE_MINUTE, true, "0.5.1", approval),
        )
        assertEquals(listOf("Waiting for approval"), parts(ApprovalState.PENDING))
        assertEquals(listOf("Not approved"), parts(ApprovalState.DENIED))
        assertEquals(listOf(SignInMethod.Google.rowLabel, PhonesCopy.count(1)), parts(ApprovalState.APPROVED))
        assertEquals("not heard yet: as today, no flash", parts(ApprovalState.APPROVED), parts(ApprovalState.UNKNOWN))
        assertEquals(listOf(YouCopy.NO_ACCOUNT), parts(ApprovalState.PENDING, signedIn = false))
        assertTrue("approval = Approval.state," in main("AccountHubScreen.kt"))
    }

    /** The Sync card: Waiting or Off with its words and no switch while the account is not approved; the switch card
     *  otherwise. */
    @Test
    fun the_sync_card_has_no_switch_while_not_approved() {
        val hub = main("AccountHubScreen.kt")
        inOrder(
            hub,
            "SettingItem.SYNC -> if (model.state == AccountState.WAITING) {",
            "ApprovalSyncCard(dark, ApprovalCopy.SYNC_WAITING_VALUE, ApprovalCopy.SYNC_WAITING_BODY, Tok.accent(dark))",
            "} else if (model.state == AccountState.NOT_APPROVED) {",
            "ApprovalSyncCard(dark, ApprovalCopy.SYNC_DENIED_VALUE, ApprovalCopy.SYNC_DENIED_BODY, Tok.textDim(dark))",
            "} else SyncCard(",
        )
        val card = slice(hub, "private fun ApprovalSyncCard(", "\n}\n")
        assertFalse("no switch", "SecondaryAction" in card || "onToggle" in card || "Switch" in card)
    }

    // ---- The gates ----

    /** The third condition: owned AND approved. Every other state is closed. */
    @Test
    fun the_cloud_gate_needs_approval_beside_ownership() {
        assertEquals(uid, Approval.gate(uid, uid, ApprovalState.APPROVED))
        for (state in listOf(ApprovalState.UNKNOWN, ApprovalState.PENDING, ApprovalState.DENIED)) {
            assertNull("$state", Approval.gate(uid, uid, state))
        }
        assertNull("ownership still counts", Approval.gate(uid, "uid-other", ApprovalState.APPROVED))
        assertNull(Approval.gate(null, uid, ApprovalState.APPROVED))
    }

    /** Every path that starts cloud work reads the approval: `ready` (push, listen, sync now), the registration and the
     *  push restart inside `registerPhone`, `registration` (the sign-in and username screens call it), the profile
     *  share's account and the settings funnel; the front event asks and listens, leaving it stops the listener. */
    @Test
    fun every_path_that_starts_cloud_work_reads_the_approval() {
        val sync = main("CloudSync.kt")
        val ready = slice(sync, "private fun ready(): Pair<String, LedgerStore>? {", "\n    }\n")
        inOrder(
            ready,
            "if (CloudGate.owned(uid, Account.storedOwner(store)) == null) return null",
            "if (!Approval.allowsCloud(Approval.cached(store, uid))) return null",
            "syncEnabled(store)",
        )
        val register = slice(sync, "    fun registerPhone(store: LedgerStore, context: Context) {", "\n    }\n")
        inOrder(
            register,
            "if (Approval.gate(uid, Account.storedOwner(store), Approval.cached(store, uid)) == null) return",
            "if (registered == uid) {", "Push.start(store)", "CloudProfile.register(uid, store, version)",
        )
        val registration = slice(sync, "    suspend fun registration(uid: String, store: LedgerStore, appVersion: String): CloudProfile.Outcome {", "\n    }\n")
        inOrder(registration, "if (!Approval.allowsCloud(approval)) {", "return CloudProfile.register(uid, store, appVersion)")
        assertTrue("accountFor(CloudSync.syncEnabled(store), uid, Account.storedOwner(store), Approval.cached(store, uid))" in main("ProfileSync.kt"))
        assertTrue("if (!writesRecord(uid, Account.storedOwner(store), Approval.cached(store, uid)) || !accepts(key, value)) return false" in main("SettingsSync.kt"))
        val active = slice(sync, "    fun appIsActive(active: Boolean) {", "\n    }\n")
        inOrder(
            active, "Approval.front(Account.uid(), LedgerStore(vaultPathOf(context)))", "registerPhone(", "push()", "listen()",
            "stopListening()", "Approval.stopListening()",
        )
    }

    /** The profile is registered nowhere but through the gate: AppRoot's username step and the username step itself
     *  call [CloudSync.registration], never `CloudProfile.register` directly (it would write a profile the rules refuse
     *  an account not approved). */
    @Test
    fun no_direct_registration_bypasses_the_gate() {
        val direct = mainSources().flatMap { (name, text) ->
            text.lines().filter { "CloudProfile.register(" in it && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") }
                .map { "$name: ${it.trim()}" }
        }
        assertEquals(
            listOf(
                "CloudSync.kt: CloudProfile.register(uid, store, version).also { if (it is CloudProfile.Outcome.Registered) registered = uid }",
                "CloudSync.kt: return CloudProfile.register(uid, store, appVersion)",
            ),
            direct,
        )
        assertTrue("withContext(Dispatchers.IO) { CloudSync.registration(uid, LedgerStore(vaultPath), version) }" in main("AppRoot.kt"))
        assertTrue("withContext(Dispatchers.IO) { CloudSync.registration(uid, store, version) }" in main("UsernameStep.kt"))
    }

    /** The registration a screen calls refuses, with the waiting or not approved words and no network, until the account
     *  is approved; another account's vault is refused as before. */
    @Test
    fun the_registration_refuses_an_account_not_approved() = runBlocking {
        val store = tempStore(owner = uid)
        assertEquals(CloudProfile.Outcome.Failed(ApprovalCopy.CALL_WAITING), CloudSync.registration(uid, store, "0.5.1"))
        Approval.setCached(store, uid, ApprovalState.PENDING)
        assertEquals(CloudProfile.Outcome.Failed(ApprovalCopy.CALL_WAITING), CloudSync.registration(uid, store, "0.5.1"))
        Approval.setCached(store, uid, ApprovalState.DENIED)
        assertEquals(CloudProfile.Outcome.Failed(ApprovalCopy.CALL_DENIED), CloudSync.registration(uid, store, "0.5.1"))
        val other = tempStore(owner = "uid-other")
        Approval.setCached(other, uid, ApprovalState.APPROVED)
        assertEquals(
            CloudProfile.Outcome.Failed("This phone's ledger belongs to another account."),
            CloudSync.registration(uid, other, "0.5.1"),
        )
    }

    /** `CloudFunctions.callJson`'s own gate: every call but the exempt ones is refused locally until approved, with the
     *  waiting words (pending, or not heard yet) or the not approved ones (denied); the exempt ones always go. Through the
     *  vault's copy, as the call reads it. */
    @Test
    fun the_call_gate_refuses_locally_and_lets_leaving_through() {
        val exempt = listOf("signOutDevice", "signOutEverywhere", "requestDeletion", "cancelDeletion", "requestApproval", "adminApprovals")
        assertEquals("the server's approvals.EXEMPT", exempt.toSet(), Approval.EXEMPT)
        for (state in ApprovalState.entries) for (name in exempt) assertNull("$name $state", Approval.localRefusal(name, state))
        val gated = listOf(
            "createInvite", "peekInvite", "acceptInvite", "findPeople", "setUsername", "setFindable", "inviteUser",
            "inviteKnown", "adoptRemovals", "deleteGroup", "groupRoles", "shareProfile",
        )
        for (name in gated) {
            assertTrue(name, Approval.localRefusal(name, ApprovalState.PENDING) is CloudFunctions.Failure.AwaitingApproval)
            assertTrue(name, Approval.localRefusal(name, ApprovalState.UNKNOWN) is CloudFunctions.Failure.AwaitingApproval)
            assertTrue(name, Approval.localRefusal(name, ApprovalState.DENIED) is CloudFunctions.Failure.NotApproved)
            assertNull(name, Approval.localRefusal(name, ApprovalState.APPROVED))
        }
        val store = tempStore(owner = uid)
        assertTrue("nothing heard: refused", CloudFunctions.gate("acceptInvite", uid, store) is CloudFunctions.Failure.AwaitingApproval)
        assertNull("leaving goes", CloudFunctions.gate("requestDeletion", uid, store))
        assertTrue("no vault: closed", CloudFunctions.gate("acceptInvite", uid, null) is CloudFunctions.Failure.AwaitingApproval)
        Approval.setCached(store, uid, ApprovalState.DENIED)
        assertTrue(CloudFunctions.gate("acceptInvite", uid, store) is CloudFunctions.Failure.NotApproved)
        Approval.setCached(store, uid, ApprovalState.APPROVED)
        assertNull("approved: the call goes", CloudFunctions.gate("acceptInvite", uid, store))
        assertTrue("another account's approval is not this one's", CloudFunctions.gate("acceptInvite", "uid-other", store) is CloudFunctions.Failure.AwaitingApproval)
    }

    /** The gate is the call's first act once signed in: before the ID token and before any request leaves the phone.
     *  The invite paths that write the group to the cloud first (create, inviteUser, inviteKnown) refuse before that. */
    @Test
    fun the_call_gate_comes_before_any_network() {
        val invites = main("Invites.kt")
        val call = slice(invites, "    suspend fun callJson(name: String, data: JsonObject): JsonObject {", "\n    }\n")
        inOrder(
            call, "FirebaseAuth.getInstance().currentUser ?: throw Failure.SignedOut()", "gate(name, user.uid)?.let { throw it }",
            "user.getIdToken(false)", "URL(url(name)).openConnection()",
            // A refusal for approval from the server (a stale copy here) asks again, so the phone hears the real state.
            "decode(reply)", "if (ApprovalCopy.failure(e) != null && name !in Approval.EXEMPT) Approval.askSoon(user.uid)", "throw e",
        )
        val create = slice(invites, "    suspend fun create(store: LedgerStore, group: NamedGroupWire, member: MemberWire): String {", "\n    }\n")
        inOrder(create, "Approval.localRefusal(\"createInvite\", Approval.cached(store, uid))?.let { throw it }", "CloudSync.ensureGroup(")
        val people = main("People.kt")
        inOrder(
            slice(people, "    suspend fun invite(store: LedgerStore, group: NamedGroupWire, member: MemberWire, person: FoundPerson) {", "\n    }\n"),
            "Approval.localRefusal(\"inviteUser\", Approval.cached(store, uid))?.let { throw it }", "CloudSync.ensureGroup(",
        )
        inOrder(
            slice(people, "    suspend fun inviteKnown(", "\n    }\n"),
            "Approval.localRefusal(\"inviteKnown\", Approval.cached(store, uid))?.let { throw it }", "CloudSync.ensureGroup(",
        )
    }

    // ---- details.approval ----

    /** The server's refusal for approval (permission-denied with `details.approval`) is its own failure, and its words
     *  are the waiting or not approved ones, never "not a member" (the same status without the detail still is). */
    @Test
    fun details_approval_maps_to_the_approval_words() {
        fun refusal(details: String?): Throwable? {
            val detail = details?.let { ""","details":{"approval":"$it"}""" } ?: ""
            return runCatching { CloudFunctions.decode("""{"error":{"status":"PERMISSION_DENIED","message":"x"$detail}}""") }.exceptionOrNull()
        }
        assertTrue(refusal("pending") is CloudFunctions.Failure.AwaitingApproval)
        assertTrue(refusal("none") is CloudFunctions.Failure.AwaitingApproval)
        assertTrue(refusal("denied") is CloudFunctions.Failure.NotApproved)
        assertEquals("the detail decides", "PERMISSION_DENIED", (refusal(null) as CloudFunctions.Failure.Refused).status)
        val waiting = refusal("pending")!!
        val denied = refusal("denied")!!
        assertEquals(ApprovalCopy.CALL_WAITING, InviteCopy.failure(waiting))
        assertEquals(ApprovalCopy.CALL_DENIED, InviteCopy.failure(denied))
        assertNotEquals(InviteCopy.NOT_MEMBER, InviteCopy.failure(waiting))
        assertEquals("a real membership refusal", InviteCopy.NOT_MEMBER, InviteCopy.failure(refusal(null)!!))
        assertEquals(ApprovalCopy.CALL_WAITING, People.failure(waiting))
        assertEquals(ApprovalCopy.CALL_DENIED, People.inviteFailure(denied))
        assertEquals(ApprovalCopy.CALL_WAITING, RolesCopy.failure(waiting, person = true))
        assertEquals(UsernameSaveFailure.Message(ApprovalCopy.CALL_WAITING), UsernameSaveFailure.from(waiting))
        assertEquals(UsernameSaveFailure.Message(ApprovalCopy.CALL_DENIED), UsernameSaveFailure.from(denied))
        assertEquals("offline stays offline", UsernameSaveFailure.Message(UsernameCopy.OFFLINE), UsernameSaveFailure.from(CloudFunctions.Failure.Offline()))
        assertFalse("the same call says the same until approved", InviteCopy.canRetry(waiting))
        assertTrue(InviteCopy.canRetry(CloudFunctions.Failure.Offline()))
        assertEquals(ApprovalState.PENDING, ApprovalCopy.state(waiting))
        assertEquals(ApprovalState.DENIED, ApprovalCopy.state(denied))
        assertNull(ApprovalCopy.state(CloudFunctions.Failure.Offline()))
        // The invite page shows a refusal for approval as its own page, with the group's name when known.
        assertEquals("Waiting for approval" to ApprovalCopy.joinWaiting("Goa trip"), JoinFailure.waiting(waiting, "Goa trip"))
        assertEquals("Not approved" to ApprovalCopy.JOIN_DENIED_BODY, JoinFailure.waiting(denied, null))
        assertNull(JoinFailure.waiting(CloudFunctions.Failure.Offline(), "Goa trip"))
    }

    /** Opening an invite while not approved makes no peek call; Join paste shows the same page; a group's invite rows,
     *  Find friends and Add by @username give way to one line. */
    @Test
    fun opening_an_invite_while_waiting_makes_no_call() {
        val invites = main("Invites.kt")
        val join = slice(invites, "fun JoinScreen(vaultPath: String, link: String,", "\n}\n")
        inOrder(
            join, "failure = InviteCopy.NEEDS_ACCOUNT", "ApprovalCopy.join(Approval.state, group = null)?.let {",
            "waiting = it", "return@LaunchedEffect", "Invites.peek(link)",
        )
        inOrder(join, "blocked != null -> ApprovalJoinBlock(blocked.first, blocked.second, dark, onBack)", "failure != null ->")
        val paste = slice(invites, "fun JoinPasteScreen(", "\n}\n")
        inOrder(paste, "if (hasAccount == false) {", "ApprovalCopy.join(Approval.state, group = null)", "ApprovalJoinBlock(", "OutlinedTextField(")
        inOrder(main("GroupScreens.kt"), "val inviteLine = ApprovalCopy.invite(Approval.state)", "CardCaption(inviteLine, dark)", "InviteCopy.invite(member.name)")
        val people = main("People.kt")
        inOrder(slice(people, "fun FindPersonScreen(", "\n}\n"), "ApprovalCopy.invite(Approval.state)?.let {", "return@PushedPage", "OutlinedTextField(")
        val add = slice(people, "    val signedIn = remember { Account.isSignedIn() }", "\n}\n")
        inOrder(add, "val invites = signedIn && ApprovalCopy.invite(Approval.state) == null", "if (signedIn && inviteLine != null) {", "} else if (signedIn) {", "val onSkipti = invites && r.participantId != null")
    }

    // ---- The vault's copy ----

    /** `<uid>:<state>`, for that account only; another account, nothing, or anything malformed reads unknown. */
    @Test
    fun the_cache_belongs_to_its_account() {
        assertEquals("a:b:approved", Approval.encode("a:b", ApprovalState.APPROVED))
        assertEquals("a colon in an id is kept", ApprovalState.APPROVED, Approval.decode("a:b:approved", "a:b"))
        assertEquals(ApprovalState.PENDING, Approval.decode("u1:pending", "u1"))
        assertEquals("another account's state is not this one's", ApprovalState.UNKNOWN, Approval.decode("u1:approved", "u2"))
        assertEquals(ApprovalState.UNKNOWN, Approval.decode("u1:maybe", "u1"))
        assertEquals(ApprovalState.UNKNOWN, Approval.decode("approved", "u1"))
        assertEquals(ApprovalState.UNKNOWN, Approval.decode(null, "u1"))
        assertEquals(ApprovalState.UNKNOWN, Approval.decode("u1:approved", null))
        assertEquals("iOS's key", "account/approval", Approval.CACHE_KEY)
    }

    /** The copy lives in the vault (a kv value, never a record: nothing to sync), and every sign-out clears it. */
    @Test
    fun the_cache_is_local_and_cleared_at_sign_out() {
        val store = tempStore()
        assertEquals(ApprovalState.UNKNOWN, Approval.cached(store, uid))
        Approval.setCached(store, uid, ApprovalState.APPROVED)
        assertEquals(ApprovalState.APPROVED, Approval.cached(store, uid))
        assertEquals(ApprovalState.UNKNOWN, Approval.cached(store, "uid-other"))
        assertEquals(ApprovalState.UNKNOWN, Approval.cached(store, null))
        assertEquals("a local value, never a record", 0, store.allRecords().size)
        Approval.signedOut(store)
        assertEquals("forgotten at sign-out", ApprovalState.UNKNOWN, Approval.cached(store, uid))
        assertEquals(ApprovalState.UNKNOWN, Approval.state)
        // A vault that cannot be read is closed.
        val broken = dir.resolve("broken.db").toFile().apply { writeBytes(ByteArray(4096) { 7 }) }.path
        assertEquals(ApprovalState.UNKNOWN, Approval.cached(LedgerStore(broken) { error("cannot open") }, uid))
        // A session ended with no sign-out here (auth's user gone at the next front event): forgotten too.
        Approval.setCached(store, uid, ApprovalState.PENDING)
        Approval.front(null, store)
        assertEquals("forgotten when no one is signed in", ApprovalState.UNKNOWN, Approval.cached(store, uid))
        assertEquals(0, store.rawKvGet(Approval.CACHE_KEY)?.size ?: 0)
        // Every sign-out comes through Account.signOut (sign-out everywhere, a deletion and a remote sign-out too).
        inOrder(slice(main("Account.kt"), "    fun signOut() {", "\n    }\n"), "auth().signOut()", "Approval.signedOut()", "CloudSync.accountSignedOut()")
    }

    /** The sign-in asks for approval after the owner is kept and before the cloud starts; the ask sends the name and the
     *  platform; the listener trusts the server only; approval arriving toasts and starts the cloud, approval taken away
     *  stops it. */
    @Test
    fun the_sign_in_asks_before_the_cloud_starts() {
        val signIn = slice(main("Account.kt"), "    suspend fun signIn(", "\n    }\n")
        inOrder(signIn, "keep(incoming, method, store) { signOut() }", "Approval.signedIn(incoming, store)", "CloudSync.appIsActive(true)")
        val approval = main("Approval.kt")
        inOrder(
            slice(approval, "    suspend fun ask(uid: String, store: LedgerStore) {", "\n    }\n"),
            "\"platform\" to JsonPrimitive(\"android\")", "CloudProfile.cloudName(", "CloudFunctions.callJson(\"requestApproval\", JsonObject(data))",
            "heard(ApprovalState.server(word), uid, store)",
        )
        val listen = slice(approval, "    fun listen(uid: String, store: LedgerStore) = synchronized(listenLock) {", "\n    }\n")
        assertTrue("if (error != null || snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener" in listen)
        assertTrue("Cloud.db().collection(\"approvals\").document(uid)" in listen)
        inOrder(
            slice(approval, "    fun heard(server: ApprovalState, uid: String, store: LedgerStore) {", "\n    }\n"),
            "is ApprovalStep.Approve -> {", "setCached(store, uid, ApprovalState.APPROVED)",
            "if (step.toast) DoneCenter.post(DoneMoments.line(ApprovalCopy.APPROVED_TOAST))", "CloudSync.appIsActive(true)",
            "is ApprovalStep.Withdraw -> {", "setCached(store, uid, step.state)", "CloudSync.approvalWithdrawn()",
        )
        inOrder(slice(main("CloudSync.kt"), "    fun approvalWithdrawn() {", "\n    }\n"), "registered = null", "stopListening()", "Push.stop(", "inCloud = false")
    }

    // ---- A pending deletion of an account with no profile ----

    /** The deletion marker is on the profile; an account with no profile (waiting or not approved) has it on its own
     *  approval document, so Keep my account is offered at its next sign-in within the 7 days. */
    @Test
    fun a_pending_deletion_is_read_from_the_approval_when_there_is_no_profile() = runBlocking {
        val at = com.google.firebase.Timestamp(1_800_000_000, 0)
        val later = com.google.firebase.Timestamp(1_800_000_100, 0)
        var approvalReads = 0
        suspend fun read(state: ApprovalState, profile: Map<String, Any?>?, approval: Map<String, Any?>?) =
            Phones.pendingDeletion(state, { profile }, { approvalReads++; approval })
        // No profile (waiting): the approval's marker.
        assertEquals(1_800_000_000_000L, read(ApprovalState.PENDING, null, mapOf("state" to "pending", "deleteAt" to at)))
        assertEquals(1_800_000_000_000L, read(ApprovalState.DENIED, null, mapOf("state" to "denied", "deleteAt" to at)))
        assertEquals("not heard yet, no profile", 1_800_000_000_000L, read(ApprovalState.UNKNOWN, null, mapOf("deleteAt" to at)))
        assertNull("no marker anywhere", read(ApprovalState.PENDING, null, mapOf("state" to "pending")))
        assertNull("no documents", read(ApprovalState.PENDING, null, null))
        // A profile's own marker first; an approved account with a profile reads nothing else.
        assertEquals(1_800_000_100_000L, read(ApprovalState.APPROVED, mapOf("deleteAt" to later), mapOf("deleteAt" to at)))
        approvalReads = 0
        assertNull(read(ApprovalState.APPROVED, mapOf("participantId" to "x"), mapOf("deleteAt" to at)))
        assertEquals("an approved account's profile is the only place", 0, approvalReads)
        // Not approved with a profile (from before the gate, approval taken away later): the approval document too.
        assertEquals(1_800_000_000_000L, read(ApprovalState.DENIED, mapOf("participantId" to "x"), mapOf("deleteAt" to at)))
        val card = slice(main("Phones.kt"), "fun DeletionPendingCard(", "\n}\n")
        inOrder(card, "Approval.cached(store, uid)", "Phones.pendingDeletion(", "collection(\"users\")", "collection(\"approvals\")")
    }

    // ---- The emulator helper ----

    /** `EmulatorApproval` approves only in debug builds (release compiles a stub that makes no call) and only against the
     *  emulators; every call to it is in a proof. */
    @Test
    fun the_emulator_helper_is_debug_only_and_emulator_only() = runBlocking {
        val debug = File("src/debug/java/com/vardrlabs/skipti/EmulatorApproval.kt").readText()
        val release = File("src/release/java/com/vardrlabs/skipti/EmulatorApproval.kt").readText()
        val approve = slice(debug, "    suspend fun approve(uid: String): Boolean {", "\n    }\n")
        assertTrue("the emulators only, first", approve.startsWith("\n        if (!Cloud.emulator) return false\n"))
        assertTrue("CloudFunctions.url(\"adminApprovals\")" in approve)
        assertTrue("put(\"sub\", ADMIN)\n            put(\"user_id\", ADMIN)" in debug)
        assertTrue("suspend fun approve(uid: String): Boolean = false" in release)
        for (word in listOf("URL", "http", "Cloud", "admin")) assertFalse("the stub makes no call: $word", word in release.substringAfter("object EmulatorApproval"))
        for ((name, text) in mainSources()) assertFalse("$name declares the helper", "object EmulatorApproval" in text)
        assertEquals("emulator-admin", EmulatorApproval.ADMIN)
        val token = EmulatorApproval.adminToken().split(".")
        assertEquals(3, token.size)
        assertEquals("unsigned", "", token[2])
        val claims = String(java.util.Base64.getUrlDecoder().decode(token[1]))
        assertTrue(claims, "\"sub\":\"emulator-admin\"" in claims && "\"sign_in_provider\":\"google.com\"" in claims)
        val was = Cloud.emulator
        Cloud.emulator = false
        try {
            assertFalse("not the emulators: nothing", EmulatorApproval.approve(uid))
        } finally {
            Cloud.emulator = was
        }
        // One call in each proof: the four Account proofs and the group-end proof.
        val calls = mainSources().flatMap { (name, text) -> text.lines().filter { "EmulatorApproval.approve(" in it }.map { name } }
        assertEquals(listOf("Account.kt", "Account.kt", "Account.kt", "Account.kt", "AppRoot.kt"), calls)
        assertTrue("object GroupEndProof" in main("AppRoot.kt"))
    }
}
