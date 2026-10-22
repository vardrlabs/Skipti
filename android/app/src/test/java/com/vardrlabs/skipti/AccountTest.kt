package com.vardrlabs.skipti

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/** D-191: the Firebase account; the twin of iOS's AccountTests. */
class AccountTest {
    /** D-222 (L58): the twin of iOS's testThePhoneCountLeavesOutSignedOutPhones. */
    @Test
    fun the_phone_count_leaves_out_signed_out_phones() {
        assertEquals("1 phone", PhonesCopy.count(1))
        assertEquals("3 phones", PhonesCopy.count(3))
        val docs = listOf(
            "a" to mapOf<String, Any?>("name" to "Pixel", "platform" to "android"),
            "b" to mapOf<String, Any?>("name" to "iPhone", "signedOut" to true),
            "c" to mapOf<String, Any?>("platform" to "ios", "signedOut" to false),
        )
        assertEquals(2, Phones.activeCount(docs))
        assertEquals(0, Phones.activeCount(emptyList()))
    }

    /** D-225 (Design 4): the Your phones row's summary on Security; the twin of iOS's
     *  testThePhonesSummaryNamesTheActivePhonesThisOneFirst. Signed-out phones are left out; none left is null, never an
     *  empty summary. */
    @Test
    fun the_phones_summary_names_the_active_phones_this_one_first() {
        fun row(id: String, name: String, signedOut: Boolean = false, isThis: Boolean = false) =
            Phones.Row(id = id, name = name, platform = "android", lastSeenMs = null, signedOut = signedOut, isThis = isThis)
        val me = row("a", "iPhone 17", isThis = true)
        val other = row("b", "Moto edge 70")
        val old = row("c", "Old phone", signedOut = true)
        assertEquals("iPhone 17, Moto edge 70", PhonesSummary.line(listOf(me, other, old)))
        assertEquals("this phone first", "iPhone 17, Moto edge 70", PhonesSummary.line(listOf(other, old, me)))
        assertNull("only signed-out phones: no summary", PhonesSummary.line(listOf(old)))
        assertNull(PhonesSummary.line(emptyList()))
    }

    /** D-225 follow-up (R2, the re-look): Your phones is never silently empty. A listen that fails (no snapshot) is
     *  [Phones.Listing.Failed]: the page says so in the app's cloud wording and offers Try again, which listens again; a
     *  listen that works lists the rows, or "No phones yet." when there are none. */
    @Test
    fun a_failed_phone_list_says_so_and_offers_try_again() {
        assertEquals(Phones.Listing.Failed, Phones.listing(null, "this"))
        val docs = listOf("this" to mapOf<String, Any?>("name" to "Pixel", "platform" to "android"))
        val listed = Phones.listing(docs, "this")
        assertTrue(listed is Phones.Listing.Listed)
        assertEquals(listOf("Pixel"), (listed as Phones.Listing.Listed).rows.map { it.name })
        assertTrue("this phone known", listed.rows.single().isThis)
        assertEquals(Phones.Listing.Listed(emptyList()), Phones.listing(emptyList(), "this"))
        assertEquals("Could not load your phones. Check your connection and try again.", PhonesCopy.LOAD_FAILED)
        assertEquals("Try again", PhonesCopy.TRY_AGAIN)
        // The page: every listener event goes through the decision; a failure shows the line and the retry, which
        // listens again (the effect is keyed by the attempt); nothing drops a failed event.
        val src = File("src/main/java/com/vardrlabs/skipti/Phones.kt").readText()
        val page = src.substringAfter("fun PhonesScreen(").substringBefore("\n}\n")
        assertFalse("no dropped event", "?: return@addSnapshotListener" in page)
        assertTrue("the effect listens again per attempt", "DisposableEffect(vaultPath, attempt) {" in page)
        assertTrue("each event through the decision",
            "addSnapshotListener { snapshot, _ ->\n                listing = Phones.listing(snapshot?.documents?.map { it.id to (it.data ?: emptyMap()) }, thisDevice)" in page)
        val failed = page.substringAfter("Phones.Listing.Failed -> {").substringBefore("\n            }\n")
        assertTrue("the error line", "ErrorLine(PhonesCopy.LOAD_FAILED, dark)" in failed)
        assertTrue("and Try again listens again", "SecondaryAction(PhonesCopy.TRY_AGAIN, dark) { attempt++ }" in failed)
        assertTrue("an empty list says so", "PhonesCopy.EMPTY" in page.substringAfter("is Phones.Listing.Listed -> if (shown.rows.isEmpty()) {"))
    }

    private class MemoryKeystore : SkiptiKeystore {
        var key: ByteArray? = null
        override fun provision(key: ByteArray) { this.key = key }
        override fun load(): ByteArray? = key
        override fun destroy() { key = null }
    }

    private fun store(): LedgerStore {
        val keystore = MemoryKeystore()
        val path = Files.createTempDirectory("account").resolve("ledger-vault.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    @Test
    fun firebase_is_configured_in_code_for_skipti_26() {
        assertEquals("skipti-26", FirebaseConfig.PROJECT_ID)
        assertEquals("1:403030719865:android:b8e41683ad5cacfacd43a8", FirebaseConfig.APP_ID)
        assertEquals("the sender id is the project number", "403030719865", FirebaseConfig.SENDER_ID)
        assertTrue(FirebaseConfig.API_KEY.startsWith("AIza"))
        assertTrue(FirebaseConfig.WEB_CLIENT_ID.startsWith("403030719865-"))
    }

    @Test
    fun one_account_per_phone() {
        assertTrue(Account.owns(null, "u1"))
        assertTrue(Account.owns("u1", "u1"))
        assertFalse(Account.owns("u1", "u2"))
    }

    /** The old relay's owner names an account that no longer exists: it must
     *  not lock a tester out of the Firebase account. */
    @Test
    fun only_the_firebase_owner_counts() {
        val store = store()
        AccountOwner.remember("relay-subject", store)
        assertNull(Account.storedOwner(store))
        store.rawKvSet(Account.OWNER_KEY, "u1".toByteArray(Charsets.UTF_8))
        assertEquals("u1", Account.storedOwner(store))
    }

    // ---- D-193: the profile and this phone in the cloud ----

    /** The rules' own lists, read from `firebase/firestore.rules`. */
    private fun rulesList(marker: String, call: String): List<String> {
        val rules = File(System.getProperty("user.dir")).resolve("../../firebase/firestore.rules").readText()
        val start = rules.indexOf(marker).also { assertTrue(marker, it >= 0) } + marker.length
        val open = rules.indexOf("$call([", start).also { assertTrue(call, it >= 0) } + call.length + 2
        val close = rules.indexOf("])", open)
        return rules.substring(open, close).split(",").map { it.trim().trim('\'') }
    }

    /** A field the rules do not allow would make every write fail; a field the
     *  rules allow but the app does not send is a hole nobody tested. */
    @Test
    fun profile_and_device_fields_are_exactly_the_rules() {
        val profile = rulesList("match /users/{uid} {", "hasOnly")
        val deviceCreate = rulesList("match /devices/{deviceId} {", "hasOnly")
        val deviceUpdate = rulesList("match /devices/{deviceId} {", "changesOnly")
        assertEquals(profile, CloudProfile.PROFILE_FIELDS)
        assertEquals(deviceCreate, CloudProfile.DEVICE_CREATE_FIELDS)
        assertEquals(deviceUpdate, CloudProfile.DEVICE_UPDATE_FIELDS)
        assertEquals(profile.toSet(), CloudProfile.profileCreate("p", "Priya").keys)
        assertEquals(profile.toSet() - "name", CloudProfile.profileCreate("p").keys)
        assertEquals(setOf("updatedAt"), CloudProfile.profileUpdate().keys)
        assertEquals(setOf("updatedAt", "name"), CloudProfile.profileUpdate("Priya").keys)
        // D-197: the rules' 1 to 60 characters.
        assertNull(CloudProfile.cloudName("   "))
        assertEquals("Priya", CloudProfile.cloudName(" Priya "))
        assertEquals(60, CloudProfile.cloudName("x".repeat(70))?.length)
        // D-196: the push address is written on its own, never at registration.
        assertEquals(deviceCreate.toSet() - "fcmToken", CloudProfile.deviceCreate("Pixel 8", "0.4.0").keys)
        assertEquals(deviceUpdate.toSet() - "fcmToken", CloudProfile.deviceUpdate("Pixel 8", "0.4.0").keys)
        assertEquals(setOf("fcmToken", "lastSeen"), CloudProfile.tokenUpdate("t").keys)
        assertEquals("t", CloudProfile.tokenUpdate("t")["fcmToken"])
        assertFalse("removal deletes the field", CloudProfile.tokenUpdate(null)["fcmToken"] is String)
        assertEquals("android", CloudProfile.deviceCreate("Pixel 8", "0.4.0")["platform"])
    }

    /** One id per phone: made once, kept, 32 lowercase hex (the rules refuse
     *  any other device document id). */
    @Test
    fun the_phones_id_is_made_once_and_is_32_hex() {
        val store = store()
        val id = CloudProfile.deviceId(store)
        assertTrue(id, Regex("^[0-9a-f]{32}$").matches(id))
        assertEquals(id, CloudProfile.deviceId(store))
        assertNotEquals(id, CloudProfile.deviceId(store()))
        // D-196 (correcting D-194): the phone's cloud id is its records' id.
        assertEquals(LedgerStore.Sync.deviceId(store), id)
    }

    // ---- D-194: your ledger as Firestore documents ----

    private fun sample(group: String? = null, device: String = "b".repeat(32), counter: UInt = 7u, millis: ULong = 1_790_000_000_123uL) =
        uniffi.skipti_core.RecordWire(
            1u, 1u, group, 0u, millis, counter, device, device,
            byteArrayOf(0x45, 0x58, 0x50, 0x01), 0u, """{"paidMinor":120000}""".toByteArray(),
        )

    /** RecordWire's generated equals compares byte arrays by reference. */
    private fun same(a: uniffi.skipti_core.RecordWire, b: uniffi.skipti_core.RecordWire): Boolean {
        val none = ByteArray(0)
        return a.copy(nonceSession = none, blob = none) == b.copy(nonceSession = none, blob = none) &&
            a.nonceSession.contentEquals(b.nonceSession) && a.blob.contentEquals(b.blob)
    }

    @Test
    fun a_record_survives_the_trip_through_a_document() {
        val record = sample()
        val document = CloudRecords.document(record, "u1")
        assertEquals("u1", document["authorUid"])
        val back = CloudRecords.record(document, null)
        assertTrue(back != null && same(record, back))
        assertEquals("1790000000123-7-" + "b".repeat(32), CloudRecords.documentId(record))
    }

    /** The document's fields are exactly the rules' `validRecord` list (bar
     *  `notice`, which group records carry from unit 6). */
    @Test
    fun a_record_document_has_only_the_rules_fields() {
        val allowed = rulesList("function validRecord() {", "hasOnly")
        assertEquals(allowed, CloudRecords.FIELDS)
        assertEquals(allowed.toSet() - "notice", CloudRecords.document(sample(), "u1").keys)
    }

    /** Only records the cloud lacks go up, oldest first, personal and group
     *  alike; a record the rules would refuse stays on the phone. */
    @Test
    fun only_pending_records_go_up() {
        val mine = sample()
        val older = sample(millis = 1_790_000_000_122uL)
        val grouped = sample(group = "c".repeat(32), counter = 8u)
        val fixture = sample(device = "device-1")
        val oddGroup = sample(group = "group-1", counter = 10u)
        val sent = sample(counter = 9u)
        val pending = CloudRecords.pending(listOf(mine, grouped, fixture, oddGroup, sent, older), setOf(CloudRecords.documentId(sent)))
        assertEquals(listOf(older, mine, grouped).map(CloudRecords::documentId), pending.map(CloudRecords::documentId))
    }

    /** A group goes up with exactly the fields the rules let a creator write,
     *  this account alone in it, and a name the rules accept. */
    @Test
    fun a_group_goes_up_with_only_its_creator() {
        val allowed = rulesList("match /groups/{groupId} {", "hasOnly")
        assertEquals(allowed, CloudRecords.GROUP_FIELDS)
        val group = CloudRecords.groupDocument("  Goa trip ", "u1")
        assertEquals(allowed.toSet() - "emoji", group.keys)
        assertEquals(listOf("u1"), group["memberUids"])
        assertEquals("u1", group["createdBy"])
        assertEquals("Goa trip", group["name"])
        assertEquals("Group", CloudRecords.groupDocument(" ", "u1")["name"])
        assertEquals(80, (CloudRecords.groupDocument("x".repeat(90), "u1")["name"] as String).length)
    }

    @Test
    fun a_malformed_document_is_not_a_record() {
        val good = CloudRecords.document(sample(), "u1")
        val bad = listOf(
            good - "hlc",
            good + ("deviceId" to "not-hex"),
            good + ("hlc" to mapOf("millis" to 1L, "counter" to UInt.MAX_VALUE.toLong() + 1, "device" to "b".repeat(32))),
            good + ("type" to "300"),
        )
        for (document in bad) assertNull(CloudRecords.record(document, null))
    }

    /** D-194: another app can start the exported activity with any extras;
     *  a release build runs no proof (they skip the app lock and read the
     *  vault), and `--emulators` is never a proof. */
    @Test
    fun a_release_build_honours_no_proof_argument() {
        assertNull(ProofArgs.proof("--list-vault", debuggable = false))
        assertNull(ProofArgs.proof("--auth-emulator-proof", debuggable = false))
        assertNull(ProofArgs.proof("--settings-emulator-proof a", debuggable = false))
        assertNull(ProofArgs.proof("--emulators", debuggable = true))
        assertNull(ProofArgs.proof("  ", debuggable = true))
        assertNull(ProofArgs.proof(null, debuggable = true))
        assertEquals("--list-vault", ProofArgs.proof(" --list-vault ", debuggable = true))
    }

    // ---- D-195: what a group's log may bring in ----

    @Test
    fun a_groups_log_brings_in_only_that_group() {
        val group = "c".repeat(32)
        val other = "e".repeat(32)
        val me = "a".repeat(32)
        val them = "b".repeat(32)
        fun record(type: Int, blob: ByteArray, device: String = them, scope: String = group) =
            uniffi.skipti_core.RecordWire(1u, type.toUByte(), scope, 0u, 1uL, 0u, device, device, ByteArray(0), 0u, blob)
        fun expense(g: String) = kotlinx.serialization.json.Json.encodeToString(
            ExpenseDTO.serializer(), ExpenseDTO(payer = me, paidMinor = 100, currencyCode = "USD", group = g, shares = listOf(ExpenseDTO.ShareDTO(me, 100))),
        ).toByteArray()
        fun op(o: uniffi.skipti_core.GroupOpIn) = uniffi.skipti_core.ffiEncodeGroupOp(o)
        fun refusal(r: uniffi.skipti_core.RecordWire) = CloudRecords.refusal(r, group, me)
        assertNull(refusal(record(1, expense(group))))
        assertNull(refusal(record(3, op(uniffi.skipti_core.GroupOpIn.RenameGroup(group, "Goa")))))
        assertEquals(CloudRecords.Refusal.WRONG_GROUP, refusal(record(1, expense(other))))
        assertEquals(CloudRecords.Refusal.WRONG_GROUP, refusal(record(3, op(uniffi.skipti_core.GroupOpIn.RenameGroup(other, "x")))))
        assertEquals(CloudRecords.Refusal.PERSONAL, refusal(record(3, op(uniffi.skipti_core.GroupOpIn.You(them, null)))))
        assertEquals(CloudRecords.Refusal.THIS_DEVICE, refusal(record(1, expense(group), device = me)))
        assertEquals(CloudRecords.Refusal.WRONG_SCOPE, refusal(record(1, expense(group), scope = other)))
        assertEquals(CloudRecords.Refusal.UNKNOWN_TYPE, refusal(record(9, ByteArray(0))))
        assertEquals(CloudRecords.Refusal.UNREADABLE, refusal(record(1, "x".toByteArray())))
    }

    // ---- D-195: invites ----

    @Test
    fun an_invite_link_carries_only_its_token() {
        val token = "A".repeat(42) + "_"
        val link = InviteLink.make(token)
        assertEquals("https://skipti.vardrlabs.com/join#$token", link)
        assertEquals(token, InviteLink.token("  $link\n"))
        for (bad in listOf(
            "http://skipti.vardrlabs.com/join#$token", "https://evil.example/join#$token",
            "https://skipti.vardrlabs.com/join#${token}A", "https://skipti.vardrlabs.com/join#short",
            "https://skipti.vardrlabs.com/join/x#$token", "https://skipti.vardrlabs.com/join#$token/",
        )) assertNull(bad, InviteLink.token(bad))
    }

    /** Firebase's callable protocol: {"result": …} or {"error": {"status"}}. */
    @Test
    fun a_functions_reply_is_its_result_or_its_refusal() {
        assertEquals("t", with(CloudFunctions) { CloudFunctions.decode("""{"result":{"token":"t"}}""").text("token") })
        val refused = runCatching { CloudFunctions.decode("""{"error":{"status":"PERMISSION_DENIED","message":"no"}}""") }
            .exceptionOrNull()
        assertEquals("PERMISSION_DENIED", (refused as CloudFunctions.Failure.Refused).status)
        assertTrue(runCatching { CloudFunctions.decode("<html>") }.exceptionOrNull() is CloudFunctions.Failure.Offline)
        assertEquals(InviteCopy.USED, InviteCopy.failure(CloudFunctions.Failure.Refused("FAILED_PRECONDITION")))
        assertEquals(InviteCopy.USED, InviteCopy.failure(CloudFunctions.Failure.Refused("NOT_FOUND")))
        assertEquals(InviteCopy.NOT_MEMBER, InviteCopy.failure(CloudFunctions.Failure.Refused("PERMISSION_DENIED")))
        assertEquals(InviteCopy.NEEDS_ACCOUNT, InviteCopy.failure(CloudFunctions.Failure.SignedOut()))
        assertEquals(InviteCopy.OFFLINE, InviteCopy.failure(CloudFunctions.Failure.Offline()))
    }

    /** D-218: inviting into a place someone already holds is refused by the cloud with
     *  `already-exists`; the phone says so in the cloud's own words. Only the invite calls
     *  read it that way: the username path still says "taken". Twin of iOS's test. */
    @Test
    fun a_held_place_has_its_own_refusal_and_the_username_path_is_untouched() {
        assertEquals("Someone has already joined as this person.", InviteCopy.HELD)
        assertEquals(InviteCopy.HELD, InviteCopy.failure(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        assertEquals(InviteCopy.HELD, People.inviteFailure(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        // Every other status reads as before, through People.failure.
        assertEquals(PeopleCopy.ALREADY_IN, People.inviteFailure(CloudFunctions.Failure.Refused("FAILED_PRECONDITION")))
        assertEquals(PeopleCopy.NO_MATCH, People.inviteFailure(CloudFunctions.Failure.Refused("NOT_FOUND")))
        assertEquals(PeopleCopy.LIMIT, People.inviteFailure(CloudFunctions.Failure.Refused("RESOURCE_EXHAUSTED")))
        assertEquals(InviteCopy.NOT_MEMBER, People.inviteFailure(CloudFunctions.Failure.Refused("PERMISSION_DENIED")))
        assertEquals(InviteCopy.OFFLINE, People.inviteFailure(CloudFunctions.Failure.Offline()))
        // The negative: the username path is untouched.
        assertEquals(PeopleCopy.USERNAME_TAKEN, People.failure(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        assertNotEquals(PeopleCopy.USERNAME_TAKEN, InviteCopy.HELD)
    }

    /** D-229 decision 52: acceptInvite refuses an account that already holds a present place in the group with
     *  `already-exists` and details `{member: true}`; the join says "You're already in this group." (a public
     *  string, verbatim, the same on iOS). The held-place refusal (no details) and every other status read as
     *  before; it is not retryable (the same call says the same thing). Twin of iOS's test. */
    @Test
    fun d229_an_invite_into_a_group_you_are_in_says_so() {
        assertEquals("You're already in this group.", InviteCopy.ALREADY_MEMBER)
        val member = runCatching {
            CloudFunctions.decode(
                """{"error":{"status":"ALREADY_EXISTS","message":"You're already in this group.","details":{"member":true}}}""",
            )
        }.exceptionOrNull()
        assertTrue("$member", member is CloudFunctions.Failure.Refused && member.member)
        assertEquals(InviteCopy.ALREADY_MEMBER, InviteCopy.failure(member!!))
        assertFalse(InviteCopy.canRetry(member))
        // The negatives: no details, other details, and member on another status read as before.
        for (body in listOf(
            """{"error":{"status":"ALREADY_EXISTS","message":"Someone has already joined as this person."}}""",
            """{"error":{"status":"ALREADY_EXISTS","message":"x","details":{"member":false}}}""",
            """{"error":{"status":"ALREADY_EXISTS","message":"x","details":{"member":"true"}}}""",
            """{"error":{"status":"ALREADY_EXISTS","message":"x","details":"member"}}""",
        )) {
            val held = runCatching { CloudFunctions.decode(body) }.exceptionOrNull()
            assertTrue(body, held is CloudFunctions.Failure.Refused && !held.member)
            assertEquals(body, InviteCopy.HELD, InviteCopy.failure(held!!))
        }
        val used = runCatching {
            CloudFunctions.decode("""{"error":{"status":"FAILED_PRECONDITION","message":"x","details":{"member":true}}}""")
        }.exceptionOrNull()!!
        assertEquals(InviteCopy.USED, InviteCopy.failure(used))
        assertEquals(InviteCopy.HELD, InviteCopy.failure(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        // The invite calls (inviteUser, inviteKnown) keep reading already-exists as a held place.
        assertEquals(InviteCopy.HELD, People.inviteFailure(member))
        // The join maps its failure through InviteCopy.failure (source pinned).
        val source = File("src/main/java/com/vardrlabs/skipti/Invites.kt").readText()
        val join = source.substringAfter("Invites.accept(link, offer, store)")
        assertTrue(join.substringAfter("onFailure = {").lineSequence().first().contains("failure = InviteCopy.failure(it)"))
    }

    /** D-218 fix (T3-m1): every failure path of `People.invite` and `People.inviteKnown` maps through
     *  `People.inviteFailure`, never `People.failure` (which reads ALREADY_EXISTS as "taken"). The source is
     *  pinned: a call written as `runCatching { People.invite(...) }` has its own `.onFailure` next; the AddPerson
     *  calls run inside `run(...)`, whose `.onFailure` is the handler. Twin of iOS's test. */
    @Test
    fun every_invite_call_site_maps_its_failure_through_invite_failure() {
        val source = File("src/main/java/com/vardrlabs/skipti/People.kt").readText()
        val handlerOfRun = source.substringAfter("fun run(who: String").substringAfter(".onFailure {").lineSequence().first()
        assertTrue(handlerOfRun, handlerOfRun.contains("People.inviteFailure("))
        assertFalse(handlerOfRun, handlerOfRun.contains("People.failure("))
        var sites = 0
        for (call in listOf("People.invite(", "People.inviteKnown(")) {
            var at = source.indexOf(call)
            while (at >= 0) {
                sites++
                val line = source.substring(source.lastIndexOf('\n', at) + 1, source.indexOf('\n', at))
                if (line.contains("runCatching")) {
                    val handler = source.substring(at).substringAfter(".onFailure {").lineSequence().first()
                    assertTrue("$call failure handler: $handler", handler.contains("People.inviteFailure("))
                    assertFalse("$call failure handler: $handler", handler.contains("People.failure("))
                } else {
                    val inside = source.substring(0, at).let { it.substring(it.lastIndexOf("run(")) }
                    assertTrue("$call is inside run(...): $line", inside.startsWith("run(") && inside.count { it == '{' } > inside.count { it == '}' })
                }
                at = source.indexOf(call, at + 1)
            }
        }
        assertEquals("invite twice (FindPerson, AddPerson) and inviteKnown once", 3, sites)
    }

    /** D-218 fix: a held place stays held until its person is removed, so the invite screen offers no
     *  "Try again" for it; every other failure still does. The screen reads `canRetry` (source pinned). */
    @Test
    fun a_held_place_is_not_retryable() {
        assertFalse(InviteCopy.canRetry(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        for (failure in listOf(
            CloudFunctions.Failure.Refused("FAILED_PRECONDITION"), CloudFunctions.Failure.Refused("PERMISSION_DENIED"),
            CloudFunctions.Failure.Refused("INTERNAL"), CloudFunctions.Failure.Offline(), CloudFunctions.Failure.SignedOut(),
        )) assertTrue("$failure", InviteCopy.canRetry(failure))
        val source = File("src/main/java/com/vardrlabs/skipti/Invites.kt").readText()
        assertTrue(source.contains("retry = InviteCopy.canRetry(it)"))
        assertTrue(source.contains("if (retry) SecondaryAction(InviteCopy.TRY_AGAIN"))
    }

    /** D-230: joining sends the held places the server marks: this account's
     *  "you" on the invite's member, built through the group's own record
     *  builder, with the call's data carrying them beside the token. A1: a
     *  rejoin also sends the removed place this account's claim held, with
     *  that claim's id, first. Twin of iOS's testJoiningSendsTheHeldPlace. */
    @Test
    fun d230_joining_sends_the_held_place() {
        val store = store()
        store.setYourName("Al")
        val you = store.ensureSelf().id
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        fun held(r: uniffi.skipti_core.RecordWire): List<String> {
            val op = uniffi.skipti_core.ffiDecodeGroupOp(r.blob)
            assertTrue("a held place", op is uniffi.skipti_core.GroupOpIn.HeldPlace)
            op as uniffi.skipti_core.GroupOpIn.HeldPlace
            return listOf(op.groupId, op.memberId, op.youId)
        }
        fun ids(data: kotlinx.serialization.json.JsonObject): List<String> =
            (data["records"] as kotlinx.serialization.json.JsonArray).map {
                ((it as kotlinx.serialization.json.JsonObject)["id"] as kotlinx.serialization.json.JsonPrimitive).content
            }
        // The clock passed in stamps them. A stamp above any real clock: setYourName
        // already stamped at the real time, and an HLC never goes back.
        val first = Invites.heldRecords(store, group, member, 4_000_000_000_000L)
        assertEquals("a first join: the new place alone", 1, first.size)
        val r = first.single()
        assertEquals(group, r.groupId)
        assertEquals(4_000_000_000_000uL, r.hlcMillis)
        assertEquals(listOf(group, member, you), held(r))
        val data = Invites.acceptData("t", first)
        assertEquals("t", (data["token"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(listOf(CloudRecords.documentId(r)), ids(data))
        assertEquals(listOf("token"), Invites.acceptData("t", emptyList()).keys.sorted())
        assertFalse("never put in the vault", store.allRecords().any { CloudRecords.heldType(it) })

        // A1: removed from place 6 (claimed with this account's "you"), invited back to `member`.
        val six = "6".repeat(32)
        val bo = "5".repeat(32)
        val them = "e".repeat(32)
        fun op(o: uniffi.skipti_core.GroupOpIn, millis: Long, device: String) =
            LedgerStore.groupOpRecord(o, uniffi.skipti_core.HlcStamp(millis.toULong(), 0u), device)
        store.applyRecords(
            listOf(
                op(
                    uniffi.skipti_core.GroupOpIn.Create(
                        group, "Trip", "INR",
                        listOf(uniffi.skipti_core.GroupOpMemberIn(bo, "Bo"), uniffi.skipti_core.GroupOpMemberIn(six, "Al")),
                    ),
                    1000, them,
                ),
                op(Invites.claim(group, six, you, LedgerStore.deviceId(store)), 1100, LedgerStore.deviceId(store)),
                op(uniffi.skipti_core.GroupOpIn.RemoveMember(group, six), 1200, them),
            ),
        )
        store.rebuildProjection()
        val again = Invites.heldRecords(store, group, member, 4_000_000_000_000L)
        assertEquals("the removed place first, then the new one", listOf(listOf(group, six, you), listOf(group, member, you)), again.map(::held))
        assertTrue(
            "stamped in that order",
            again.map { it.hlcMillis to it.hlcCounter }.let { it == it.sortedWith(compareBy({ s -> s.first }, { s -> s.second })) && it.distinct().size == 2 },
        )
        assertEquals(again.map(CloudRecords::documentId), ids(Invites.acceptData("t", again)))
        assertFalse("never put in the vault", store.allRecords().any { CloudRecords.heldType(it) })
    }

    /** D-230 A5 (review I2): the new place's record names the account's "you"
     *  (the projection's, its earliest id), never this phone's own id when
     *  they differ. Final fix wave item 2: a brand-new install (no "you" yet)
     *  mints it in the build, as `accept` does right after the call, so its
     *  first join sends exactly one record naming the minted "you".
     *  Twin of iOS's testTheJoinSendsTheAccountsYou. */
    @Test
    fun d230_the_join_sends_the_accounts_you_and_a_new_install_mints_one() {
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        val empty = store()
        assertNull(empty.projectGroups().you)
        val newInstall = Invites.heldRecords(empty, group, member, 4_000_000_000_000L)
        val minted = empty.projectGroups().you?.id
        assertNotNull("the build minted the you", minted)
        assertEquals(minted, empty.ensureSelf().id)
        assertEquals("one record, naming the minted you", 1, newInstall.size)
        assertEquals(uniffi.skipti_core.GroupOpIn.HeldPlace(group, member, minted!!), uniffi.skipti_core.ffiDecodeGroupOp(newInstall.single().blob))
        val firstData = Invites.acceptData("t", newInstall)
        assertEquals(listOf("records", "token"), firstData.keys.sorted())
        assertNull(HeldRule.check(firstData["records"] as kotlinx.serialization.json.JsonArray, Invites.JOIN_MAX, invite = group to member))
        assertFalse("never put in the vault", empty.allRecords().any { CloudRecords.heldType(it) })

        // A two-phone account: this phone's id is not the account's "you" (an earlier one, another phone's).
        val store = store()
        store.setYourName("Al")
        val me = store.ensureSelf().id
        val first = "1".repeat(32)
        store.applyRecords(
            listOf(LedgerStore.groupOpRecord(uniffi.skipti_core.GroupOpIn.You(first, "Al"), uniffi.skipti_core.HlcStamp(1000uL, 0u), "9".repeat(32))),
        )
        store.rebuildProjection()
        val you = store.projectGroups().you!!.id
        assertEquals(first, you)
        assertNotEquals("the case bites", me, you)
        val op = uniffi.skipti_core.ffiDecodeGroupOp(Invites.heldRecords(store, group, member, 4_000_000_000_000L).single().blob)
        assertEquals("the account's you, not this phone's", uniffi.skipti_core.GroupOpIn.HeldPlace(group, member, you), op)
    }

    /** Final fix wave item 3 (iOS M5): the join's records are built one place
     *  at a time, so one old pair whose record core cannot build is left out
     *  and never takes the new place's record with it; the new place's record
     *  is required (none: no records, the join sends the token alone). Twin
     *  of iOS's testTheJoinBuildsOnePlaceAtATime. */
    @Test
    fun d230_one_old_place_that_cannot_build_never_drops_the_new_one() {
        val store = store()
        store.setYourName("Al")
        val you = store.ensureSelf().id
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        val six = "6".repeat(32)
        val bad = PlaceShare.Held(group, "not a place", you)
        val old = PlaceShare.Held(group, six, you)
        val place = PlaceShare.Held(group, member, you)
        fun held(records: List<uniffi.skipti_core.RecordWire>) = records.map { uniffi.skipti_core.ffiDecodeGroupOp(it.blob) }
        fun op(h: PlaceShare.Held) = uniffi.skipti_core.GroupOpIn.HeldPlace(h.groupId, h.memberId, h.youId)
        assertTrue("all at once: none", runCatching { PlaceShare.records(store, listOf(bad, place), 4_000_000_000_000L) }.isFailure)
        assertEquals("the bad old pair is left out, the new place goes", listOf(op(place)), held(Invites.joinBuilt(store, listOf(bad, place), 4_000_000_000_000L)))
        assertEquals(listOf(op(old), op(place)), held(Invites.joinBuilt(store, listOf(old, bad, place), 4_000_000_000_000L)))
        assertEquals("no record for the new place: none", emptyList<uniffi.skipti_core.RecordWire>(), Invites.joinBuilt(store, listOf(old, bad), 4_000_000_000_000L))
        assertEquals(emptyList<uniffi.skipti_core.RecordWire>(), Invites.joinBuilt(store, emptyList(), 4_000_000_000_000L))
        // heldRecords builds through it.
        val source = File("src/main/java/com/vardrlabs/skipti/Invites.kt").readText()
        val body = source.substringAfter("fun heldRecords(").substringBefore("fun joinBuilt(")
        assertTrue(body, body.contains("return joinBuilt(store, old + place, nowMs)"))
    }

    /** Final fix wave item 4 (review m1, as A8 amends it): a vault claim on the
     *  invite's own member with the account's "you" is the new place's own
     *  triple, sent ONCE (the filter: a triple twice is refused whole); a claim
     *  on that member under another of the account's ids is its own record
     *  (A8: at least one record for the invite's place, each checked alone).
     *  Twin of iOS's testAClaimOnTheInvitesOwnPlaceIsSentOnce. */
    @Test
    fun d230_a_claim_on_the_invites_own_place_is_sent_once_and_another_ids_on_its_own() {
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        val them = "e".repeat(32)
        fun op(o: uniffi.skipti_core.GroupOpIn, millis: Long, by: String) =
            LedgerStore.groupOpRecord(o, uniffi.skipti_core.HlcStamp(millis.toULong(), 0u), by)
        fun withClaim(store: LedgerStore, you: String) {
            val device = LedgerStore.deviceId(store)
            store.applyRecords(
                listOf(
                    op(
                        uniffi.skipti_core.GroupOpIn.Create(
                            group, "Trip", "INR",
                            listOf(uniffi.skipti_core.GroupOpMemberIn("5".repeat(32), "Bo"), uniffi.skipti_core.GroupOpMemberIn(member, "Al")),
                        ),
                        1000, them,
                    ),
                    op(Invites.claim(group, member, you, device), 1100, device),
                ),
            )
            store.rebuildProjection()
        }
        fun held(records: List<uniffi.skipti_core.RecordWire>) = records.map { uniffi.skipti_core.ffiDecodeGroupOp(it.blob) }
        val one = store()
        one.setYourName("Al")
        val me = one.ensureSelf().id
        withClaim(one, me)
        assertTrue(PlaceShare.Held(group, member, me) in PlaceShare.claimed(one.projectGroups(), one.projectGroups().groups.single(), one.allRecords()))
        val once = Invites.heldRecords(one, group, member, 4_000_000_000_000L)
        assertEquals("the place's own triple, once", listOf<uniffi.skipti_core.GroupOpIn>(uniffi.skipti_core.GroupOpIn.HeldPlace(group, member, me)), held(once))
        assertNull(HeldRule.check(Invites.acceptData("t", once)["records"] as kotlinx.serialization.json.JsonArray, Invites.JOIN_MAX, invite = group to member))

        // A two-phone account: this phone's id claimed the place, the account's "you" is an earlier id.
        val two = store()
        two.setYourName("Al")
        val phone = two.ensureSelf().id
        val first = "1".repeat(32)
        two.applyRecords(
            listOf(op(uniffi.skipti_core.GroupOpIn.You(first, "Al"), 900, "9".repeat(32))),
        )
        withClaim(two, phone)
        assertEquals(first, two.projectGroups().you!!.id)
        val both = Invites.heldRecords(two, group, member, 4_000_000_000_000L)
        assertEquals(
            "the other id's claim, then the new place",
            listOf<uniffi.skipti_core.GroupOpIn>(
                uniffi.skipti_core.GroupOpIn.HeldPlace(group, member, phone),
                uniffi.skipti_core.GroupOpIn.HeldPlace(group, member, first),
            ),
            held(both),
        )
        assertNull(HeldRule.check(Invites.acceptData("t", both)["records"] as kotlinx.serialization.json.JsonArray, Invites.JOIN_MAX, invite = group to member))
    }

    /** D-230 A5 (review I5): acceptInvite takes 1 to 11 records, so the join
     *  sends at most ten claimed places, in member order, and the new place
     *  last, which the cut never drops. Twelve claimed: the first ten go. The
     *  call passes the server's rule ([HeldRule], max 11). Twin of iOS's
     *  testTheJoinSendsAtMostElevenPlaces. */
    @Test
    fun d230_the_join_sends_at_most_ten_claimed_places_then_the_new_one() {
        val store = store()
        store.setYourName("Al")
        val you = store.ensureSelf().id
        val device = LedgerStore.deviceId(store)
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        val them = "e".repeat(32)
        val places = (0 until 12).map { "%032x".format(0x100 + it) }
        fun op(o: uniffi.skipti_core.GroupOpIn, millis: Long, by: String) =
            LedgerStore.groupOpRecord(o, uniffi.skipti_core.HlcStamp(millis.toULong(), 0u), by)
        store.applyRecords(
            listOf(
                op(
                    uniffi.skipti_core.GroupOpIn.Create(group, "Trip", "INR", listOf(uniffi.skipti_core.GroupOpMemberIn("5".repeat(32), "Bo"))),
                    1000, them,
                ),
            ) + places.reversed().mapIndexed { i, m ->
                op(uniffi.skipti_core.GroupOpIn.AddMember(group, uniffi.skipti_core.GroupOpMemberIn(m, "Al $i")), 1100L + i, them)
            } + places.mapIndexed { i, m -> op(Invites.claim(group, m, you, device), 1300L + i, device) },
        )
        store.rebuildProjection()
        val g = store.projectGroups().groups.single { it.group.id == group }
        // Member order is the group's (the order they were added), not the ids' order nor the claims'
        // order: added from 0x10b down, claimed from 0x100 up (so only the sort gives this order).
        val order = places.reversed()
        assertEquals("twelve claimed, in member order", order, PlaceShare.claimed(store.projectGroups(), g, store.allRecords()).map { it.memberId })
        // Core already lists its claims in member order; the sort is what orders the pairs only the vault
        // holds (A7: a void claim). With core's list emptied and the records in the order they were
        // claimed (0x100 up), still the member order.
        assertEquals(
            "vault-only pairs, in member order",
            order,
            PlaceShare.claimed(store.projectGroups(), g.copy(claims = emptyList()), store.allRecords().sortedBy { it.hlcMillis }).map { it.memberId },
        )
        val records = Invites.heldRecords(store, group, member, 4_000_000_000_000L)
        val held = records.map { uniffi.skipti_core.ffiDecodeGroupOp(it.blob) as uniffi.skipti_core.GroupOpIn.HeldPlace }
        assertEquals(Invites.JOIN_MAX, records.size)
        assertEquals("ten claimed, in member order, then the new place", order.take(10) + member, held.map { it.memberId })
        assertTrue(held.all { it.groupId == group && it.youId == you })
        val data = Invites.acceptData("t", records)
        assertNull(HeldRule.check(data["records"] as kotlinx.serialization.json.JsonArray, Invites.JOIN_MAX, invite = group to member))
    }

    /** D-230 A5 (review I1): the join's records pass the server's own rule
     *  ([HeldRule]: no (group, member, you) twice, 32-hex ids), with the new
     *  place last, for the first join and for a rejoin. */
    @Test
    fun d230_the_join_records_pass_the_servers_rule() {
        val store = store()
        store.setYourName("Al")
        val you = store.ensureSelf().id
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        val six = "6".repeat(32)
        val them = "e".repeat(32)
        fun op(o: uniffi.skipti_core.GroupOpIn, millis: Long, by: String) =
            LedgerStore.groupOpRecord(o, uniffi.skipti_core.HlcStamp(millis.toULong(), 0u), by)
        fun check(records: List<uniffi.skipti_core.RecordWire>) {
            assertNull(HeldRule.check(Invites.acceptData("t", records)["records"] as kotlinx.serialization.json.JsonArray, Invites.JOIN_MAX, invite = group to member))
            val last = uniffi.skipti_core.ffiDecodeGroupOp(records.last().blob)
            assertEquals("the new place last", uniffi.skipti_core.GroupOpIn.HeldPlace(group, member, you), last)
        }
        check(Invites.heldRecords(store, group, member, 4_000_000_000_000L))
        store.applyRecords(
            listOf(
                op(
                    uniffi.skipti_core.GroupOpIn.Create(
                        group, "Trip", "INR",
                        listOf(uniffi.skipti_core.GroupOpMemberIn("5".repeat(32), "Bo"), uniffi.skipti_core.GroupOpMemberIn(six, "Al")),
                    ),
                    1000, them,
                ),
                op(Invites.claim(group, six, you, LedgerStore.deviceId(store)), 1100, LedgerStore.deviceId(store)),
                op(uniffi.skipti_core.GroupOpIn.RemoveMember(group, six), 1200, them),
            ),
        )
        store.rebuildProjection()
        val again = Invites.heldRecords(store, group, member, 4_100_000_000_000L)
        assertEquals(2, again.size)
        check(again)
        // The rule bites (final fix wave item 6): no record for the invite's place, a record of another
        // group, a payload that is not the server's shape.
        fun wire(records: List<uniffi.skipti_core.RecordWire>) = Invites.acceptData("t", records)["records"] as kotlinx.serialization.json.JsonArray
        val oldOnly = wire(PlaceShare.records(store, listOf(PlaceShare.Held(group, six, you)), 4_200_000_000_000L))
        assertNull("alone it is a well formed record", HeldRule.check(oldOnly, Invites.JOIN_MAX))
        assertEquals("no record for the invite's place", "records", HeldRule.check(oldOnly, Invites.JOIN_MAX, invite = group to member))
        val elsewhere = wire(PlaceShare.records(store, listOf(PlaceShare.Held("f".repeat(32), member, you), PlaceShare.Held(group, member, you)), 4_200_000_000_000L))
        assertEquals("another group's record", "records", HeldRule.check(elsewhere, Invites.JOIN_MAX, invite = group to member))
        val fine = wire(again)
        fun edited(key: String, value: kotlinx.serialization.json.JsonElement) = kotlinx.serialization.json.JsonArray(
            fine.map {
                val r = it as kotlinx.serialization.json.JsonObject
                kotlinx.serialization.json.JsonObject(r + ("payload" to kotlinx.serialization.json.JsonObject((r["payload"] as kotlinx.serialization.json.JsonObject) + (key to value))))
            },
        )
        assertNull(HeldRule.check(edited("nonce", kotlinx.serialization.json.JsonPrimitive("R1JQAQ==")), Invites.JOIN_MAX, invite = group to member))
        assertEquals("records", HeldRule.check(edited("nonce", kotlinx.serialization.json.JsonPrimitive("")), Invites.JOIN_MAX, invite = group to member))
        assertEquals("records", HeldRule.check(edited("blob", kotlinx.serialization.json.JsonPrimitive("not base64!")), Invites.JOIN_MAX, invite = group to member))
        assertEquals("records", HeldRule.check(edited("version", kotlinx.serialization.json.JsonPrimitive("1")), Invites.JOIN_MAX, invite = group to member))
        assertEquals("records", HeldRule.check(edited("flags", kotlinx.serialization.json.JsonPrimitive(1.5)), Invites.JOIN_MAX, invite = group to member))
    }

    /** D-230 (review M2): peekInvite's answer carries the invite's place into
     *  the offer (an older Function's answer: none); the join sends the token
     *  alone with no offer, an offer without a place, or held places that
     *  cannot be built; `accept` sends what `joinRecords` gives (source
     *  pinned: the call goes to the network). Twin of iOS's
     *  testTheOfferCarriesThePlace. */
    @Test
    fun d230_the_offer_carries_the_place_and_the_join_never_waits_on_it() {
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        val offer = Invites.offer(
            CloudFunctions.decode(
                """{"result":{"groupName":"Trip","memberName":"Al","inviterName":"Bo","groupId":"$group","memberId":"$member"}}""",
            ),
        )
        assertEquals(Invites.Offer("Trip", "Al", "Bo", group, member), offer)
        val older = Invites.offer(CloudFunctions.decode("""{"result":{"groupName":"Trip","memberName":"Al"}}"""))
        assertEquals(Invites.Offer("Trip", "Al", null, null, null), older)
        assertTrue(runCatching { Invites.offer(CloudFunctions.decode("""{"result":{"memberName":"Al"}}""")) }.exceptionOrNull() is CloudFunctions.Failure.Offline)

        val store = store()
        store.setYourName("Al")
        assertEquals("the offer's place: sent", 1, Invites.joinRecords(offer, store, 4_000_000_000_000L).size)
        assertEquals(listOf("token"), Invites.acceptData("t", Invites.joinRecords(null, store)).keys.sorted())
        assertEquals(listOf("token"), Invites.acceptData("t", Invites.joinRecords(older, store)).keys.sorted())
        assertEquals(emptyList<uniffi.skipti_core.RecordWire>(), Invites.joinRecords(offer.copy(memberId = null), store))
        // A build that fails sends the token alone: core refuses that offer's record, and the join's
        // build (one place at a time, the new place required) gives none.
        val bad = offer.copy(groupId = "not a group")
        assertTrue(runCatching { PlaceShare.records(store, listOf(PlaceShare.Held(bad.groupId!!, member, store.ensureSelf().id))) }.isFailure)
        assertEquals(emptyList<uniffi.skipti_core.RecordWire>(), Invites.heldRecords(store, bad.groupId!!, member))
        assertEquals(listOf("token"), Invites.acceptData("t", Invites.joinRecords(bad, store)).keys.sorted())
        // A build that THROWS (a vault that will not open: the projection, the vault read and the mint all
        // go through it) still sends the token alone: `joinRecords`' guard, not the build, decides that.
        val broken = LedgerStore(Files.createTempDirectory("account").resolve("ledger-vault.db").toString()) {
            throw IllegalStateException("the vault will not open")
        }
        assertTrue("the build throws", runCatching { Invites.heldRecords(broken, group, member) }.exceptionOrNull() is IllegalStateException)
        assertEquals(emptyList<uniffi.skipti_core.RecordWire>(), Invites.joinRecords(offer, broken))
        assertEquals(listOf("token"), Invites.acceptData("t", Invites.joinRecords(offer, broken)).keys.sorted())
        // accept sends joinRecords' records, built off the main thread.
        val source = File("src/main/java/com/vardrlabs/skipti/Invites.kt").readText()
        val accept = source.substringAfter("suspend fun accept(link: String, offer: Offer?, store: LedgerStore)").substringBefore("val group =")
        assertTrue(accept, accept.contains("withContext(Dispatchers.IO) { joinRecords(offer, store) }"))
        assertTrue(accept, accept.contains("""CloudFunctions.callJson("acceptInvite", acceptData(token, records))"""))
        assertTrue(source.contains("return offer(CloudFunctions.call(\"peekInvite\""))
    }

    /** Joining writes a claim core accepts: the named member, this account's
     *  "you", this phone. */
    @Test
    fun joining_claims_the_named_person() {
        val group = "c".repeat(32)
        val member = "d".repeat(32)
        val you = "a".repeat(32)
        val device = "b".repeat(32)
        val blob = uniffi.skipti_core.ffiEncodeGroupOp(Invites.claim(group, member, you, device))
        val op = uniffi.skipti_core.ffiDecodeGroupOp(blob) as uniffi.skipti_core.GroupOpIn.Claim
        assertEquals(listOf(group, member, you), listOf(op.groupId, op.memberId, op.youId))
        assertEquals(listOf(device), op.devices.map { it.deviceId })
    }

    /** The creator claims their own place once; after that nothing more is
     *  written. */
    @Test
    fun the_creator_claims_their_own_place_once() {
        val store = store()
        store.setYourName("Al")
        val group = store.createGroup("Goa trip", "USD", listOf("Bo"))
        val device = LedgerStore.Sync.deviceId(store)
        val first = CloudRecords.ownClaims(store.projectGroups(), device)
        assertEquals(1, first.size)
        val claim = first.single() as uniffi.skipti_core.GroupOpIn.Claim
        assertEquals(listOf(group.group.id, group.selfId, group.selfId), listOf(claim.groupId, claim.memberId, claim.youId))
        store.appendGroupOps(first, Placing.NONE)
        assertEquals(emptyList<uniffi.skipti_core.GroupOpIn>(), CloudRecords.ownClaims(store.projectGroups(), device))
    }

    // ---- D-196: push ----

    private val me32 = "d".repeat(32)
    private val group32 = "c".repeat(32)
    private val expenseId = "1790000000123:7:$me32"

    private fun expenseRecord(type: UByte = 1u, group: String?, device: String, blob: ByteArray = "{}".toByteArray(), counter: UInt = 7u) =
        uniffi.skipti_core.RecordWire(
            1u, type, group, 0u, 1_790_000_000_123uL, counter, device, device,
            byteArrayOf(0x45, 0x58, 0x50, 0x01), 0u, blob,
        )

    private fun pushFixture(note: String? = "Dinner"): Map<String, uniffi.skipti_core.ProjectedExpenseOut> {
        val expense = uniffi.skipti_core.ProjectedExpenseOut(
            expenseId, uniffi.skipti_core.ExpenseInput("a1", 120_000L, "INR", emptyList(), group32, null),
            note, null, null, "a1", 1uL, false, null, null, emptyList(), emptyList(),
        )
        return mapOf(expenseId to expense)
    }

    /** Goldens by hand: what, the note as the title, and core's amount. D-257: the phone names nobody (the Function
     *  adds the author's name as the server holds it) and writes no line of its own. */
    @Test
    fun a_new_group_expense_carries_its_push_line() {
        val expenses = pushFixture()
        val n = PushNotice.notice(expenseRecord(group = group32, device = me32), me32, expenses, uniffi.skipti_core.LocaleTag.EN_IN)
        assertEquals(PushNotice.Notice(expenseId, ActivityFeed.Kind.ADDED, "Dinner", "₹1,200.00"), n)
        val document = CloudRecords.document(expenseRecord(group = group32, device = me32), "u1", n)
        assertEquals(
            mapOf("expense" to expenseId, "kind" to "added", "title" to "Dinner", "amount" to "₹1,200.00"),
            document["notice"],
        )
    }

    @Test
    fun an_edit_and_a_delete_carry_their_push_lines() {
        val expenses = pushFixture(note = null)
        val edit = uniffi.skipti_core.ffiEncodeExpenseRevision(
            uniffi.skipti_core.ExpenseRevisionIn(expenseId, emptyList(), "a1", null, "Lunch", null, null),
        )
        val delete = uniffi.skipti_core.ffiEncodeExpenseRevision(
            uniffi.skipti_core.ExpenseRevisionIn(expenseId, emptyList(), "a1", null, null, null, true),
        )
        val en = uniffi.skipti_core.LocaleTag.EN_IN
        assertEquals(
            PushNotice.Notice(expenseId, ActivityFeed.Kind.CHANGED, "an expense", "₹1,200.00"),
            PushNotice.notice(expenseRecord(5u, group32, me32, edit), me32, expenses, en),
        )
        val deleted = PushNotice.notice(expenseRecord(5u, group32, me32, delete), me32, expenses, en)
        assertEquals(PushNotice.Notice(expenseId, ActivityFeed.Kind.DELETED, "an expense", null), deleted)
        assertEquals(
            "a delete carries no amount (the rules refuse one)",
            mapOf("expense" to expenseId, "kind" to "deleted", "title" to "an expense"),
            deleted?.let(PushNotice::field),
        )
    }

    /** Negative: no line for another phone's record, a personal record, a
     *  record that is not an expense, an expense this phone does not hold, or
     *  a group the expense is not in; a long note is shortened. */
    @Test
    fun only_this_phones_group_expense_changes_carry_a_push_line() {
        val expenses = pushFixture()
        val en = uniffi.skipti_core.LocaleTag.EN_IN
        fun line(r: uniffi.skipti_core.RecordWire) = PushNotice.notice(r, me32, expenses, en)
        assertNull(line(expenseRecord(group = group32, device = "e".repeat(32))))
        // Another phone's change to an expense this phone holds (D-196's first
        // sabotage of this guard stayed green without it: the line above names
        // an expense the fixture does not hold).
        val theirDelete = uniffi.skipti_core.ffiEncodeExpenseRevision(
            uniffi.skipti_core.ExpenseRevisionIn(expenseId, emptyList(), "b1", null, null, null, true),
        )
        assertNull(line(expenseRecord(5u, group32, "e".repeat(32), theirDelete)))
        assertTrue(line(expenseRecord(5u, group32, me32, theirDelete)) != null)
        assertNull(line(expenseRecord(group = null, device = me32)))
        assertNull(line(expenseRecord(3u, group32, me32)))
        assertNull(line(expenseRecord(2u, group32, me32)))
        assertNull(line(expenseRecord(5u, group32, me32, "junk".toByteArray())))
        assertNull(line(expenseRecord(group = "f".repeat(32), device = me32)))
        assertNull(line(expenseRecord(group = group32, device = me32, counter = 8u)))
        assertNull(
            "a personal record never carries one",
            CloudRecords.document(
                expenseRecord(group = null, device = me32), "u1", PushNotice.Notice("1:2:$me32", ActivityFeed.Kind.ADDED, "t", null),
            )["notice"],
        )
        val long = pushFixture(note = "x".repeat(300))
        val n = PushNotice.notice(expenseRecord(group = group32, device = me32), me32, long, en)
        assertEquals("x".repeat(59) + "…", n?.title)
        val lines = pushFixture(note = "Dinner\nwith Bob")
        assertEquals("Dinner with Bob", PushNotice.notice(expenseRecord(group = group32, device = me32), me32, lines, en)?.title)
    }

    /** D-257: a title is one line, at most 60 UTF-16 units (the rules count them so and take 61), cut at a whole
     *  code point. Goldens by hand (re-derived in Python, .drafts/fable/pass-051/d257/goldens.py); iOS's twin is
     *  AccountTests.testAPushTitleIsOneLineCutAtAWholeCharacter. */
    @Test
    fun a_push_title_is_one_line_cut_at_a_whole_character() {
        val emoji = "\uD83D\uDE00"
        fun x(n: Int) = "x".repeat(n)
        fun e(n: Int) = emoji.repeat(n)
        assertEquals("Dinner with Bob", PushNotice.title("Dinner\nwith Bob"))
        assertEquals("Dinner with Bob", PushNotice.title("  Dinner\r\n\r\nwith\u2028Bob\t "))
        assertEquals("Din ner x", PushNotice.title("Din\u0085ner\u0000x"))
        assertEquals("an expense", PushNotice.title(null))
        assertEquals("an expense", PushNotice.title(" \n\t "))
        assertEquals("60 units stay whole", x(60), PushNotice.title(x(60)))
        assertEquals(x(59) + "…", PushNotice.title(x(61)))
        assertEquals("30 emoji are 60 units", e(30), PushNotice.title(e(30)))
        assertEquals(e(29) + "…", PushNotice.title(e(31)))
        assertEquals("x" + e(29) + "…", PushNotice.title("x" + e(40)))
        assertEquals("never half an emoji", "xx" + e(28) + "…", PushNotice.title("xx" + e(40)))
        assertEquals("Caf\u00E9 \u20B9 " + x(52) + "…", PushNotice.title("Caf\u00E9 \u20B9 " + x(70)))
        for (note in listOf(x(300), e(300), "x" + e(300), "xx" + e(300), "a\nb" + x(100))) {
            val title = PushNotice.title(note)
            assertTrue("the rules' bound", title.length <= 61)
            assertTrue("one line", title.none { Character.getType(it) == Character.CONTROL.toInt() || it == '\u2028' || it == '\u2029' })
            assertTrue("no lone surrogate", title.indices.all { i ->
                !Character.isHighSurrogate(title[i]) || (i + 1 < title.length && Character.isLowSurrogate(title[i + 1]))
            })
        }
        assertEquals("\u20B91,200.00", PushNotice.oneLine("\u20B91,200.00"))
    }

    /** A tap opens only an expense named in the shapes the rules allow. */
    @Test
    fun a_tapped_push_opens_only_a_well_formed_expense() {
        fun target(vararg pairs: Pair<String, String>) = PushRoute.target { key -> pairs.toMap()[key] }
        assertEquals(
            PushRoute.Target(group32, expenseId),
            target("kind" to "record", "groupId" to group32, "expense" to expenseId),
        )
        assertNull(target("kind" to "invite", "groupId" to group32, "expense" to expenseId))
        assertNull(target("kind" to "record", "groupId" to "g1", "expense" to expenseId))
        assertNull(target("kind" to "record", "groupId" to group32, "expense" to "1:2:" + me32.uppercase()))
        assertNull(target("kind" to "record", "groupId" to group32, "expense" to "1:2:$me32:9"))
        assertNull(target("kind" to "record", "groupId" to group32))
        assertNull(target())
    }

    /** A group's Recent activity lists that group only. */
    @Test
    fun recent_activity_lists_one_group() {
        fun e(id: String, group: String?) = uniffi.skipti_core.ProjectedExpenseOut(
            id, uniffi.skipti_core.ExpenseInput("r", 1L, "INR", emptyList(), group, null),
            id, null, null, "r", 1uL, false, null, null, emptyList(), emptyList(),
        )
        val all = listOf(e("x1", "g1"), e("x2", "g2"), e("x3", null))
        assertEquals(listOf("x2"), ActivityFeed.items(all, setOf("m"), "g2").map { it.expenseId })
        assertEquals(setOf("x1", "x2"), ActivityFeed.items(all, setOf("m")).map { it.expenseId }.toSet())
        assertEquals("Recent activity", ActivityFeed.GROUP_TITLE)
        assertEquals("Nothing new yet. What others add, change or delete in this group shows here.", ActivityFeed.GROUP_EMPTY)
    }

    @Test
    fun the_push_words_match_ios() {
        assertEquals("Notifications", PushCopy.TITLE)
        assertEquals("You hear when someone adds, changes or deletes an expense in your groups.", PushCopy.EXPLAIN_ON)
        assertEquals("Hear when someone adds, changes or deletes an expense in your groups.", PushCopy.EXPLAIN_ASK)
        assertEquals("Notifications for SKIPTI are off in your phone's settings.", PushCopy.EXPLAIN_DENIED)
        assertEquals("Notifications come with sync. Turn sync on to hear about your groups.", PushCopy.EXPLAIN_SYNC_OFF)
        assertEquals("Turn on notifications", PushCopy.TURN_ON)
        assertEquals("Open phone settings", PushCopy.OPEN_SETTINGS)
        assertEquals("Bringing in this expense…", PushCopy.FETCHING)
        assertEquals("group-activity", Push.CHANNEL_ID)
    }

    /** The manifest keeps FCM from starting on its own and draws background
     *  pushes on the channel the Function names. */
    @Test
    fun fcm_never_starts_on_its_own() {
        val manifest = File(System.getProperty("user.dir")).resolve("src/main/AndroidManifest.xml").readText()
        fun meta(name: String) = Regex("""android:name="$name"\s+android:(value|resource)="([^"]*)"""").find(manifest)?.groupValues?.get(2)
        assertEquals("false", meta("firebase_messaging_auto_init_enabled"))
        assertEquals("false", meta("delivery_metrics_exported_to_big_query_enabled"))
        assertEquals(Push.CHANNEL_ID, meta("com.google.firebase.messaging.default_notification_channel_id"))
        val functions = File(System.getProperty("user.dir")).resolve("../../firebase/functions/push.js").readText()
        assertTrue("the Function posts on this channel", functions.contains("channelId: '${Push.CHANNEL_ID}'"))
    }

    // ---- D-197: finding people ----

    /** sha256("priya@example.com"), derived with Python's hashlib, as in
     *  firebase/tests/people.test.mjs: the phone and the cloud agree. */
    private val priya = "6bdb7e961d54ddc243ec721e6e211ac0a179b19a9ce2ad52a418198e146e69fe"

    @Test
    fun an_email_fingerprint_is_the_clouds_own() {
        assertEquals(priya, EmailFingerprint.hash(" Priya@Example.COM "))
        assertNotEquals(priya, EmailFingerprint.hash("priya+x@example.com"))
        assertTrue(EmailFingerprint.looksLikeEmail("priya@example.com"))
        assertFalse(EmailFingerprint.looksLikeEmail("priya@example"))
        assertFalse(EmailFingerprint.looksLikeEmail("pri ya@example.com"))
    }

    @Test
    fun a_search_is_a_username_or_a_whole_email() {
        assertEquals(PeopleQuery.Username("priya_92"), PeopleQuery.parse(" @Priya_92 "))
        assertEquals(PeopleQuery.Email("Priya@Example.com"), PeopleQuery.parse("Priya@Example.com"))
        assertNull(PeopleQuery.parse("pr"))
        assertNull(PeopleQuery.parse("priya@"))
        assertNull(PeopleQuery.parse("pri ya"))
        assertNull(PeopleQuery.parse("a".repeat(21)))
        assertNull(PeopleQuery.parse(""))
    }

    @Test
    fun a_result_reads_as_their_name_handle_or_contact() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"people":[{"uid":"u1","name":"Priya","username":"priya","matched":"$priya"},""" +
                """{"uid":"u2","name":null,"username":null,"matched":"not-a-hash"},{"name":"No uid"}]}""",
        ) as kotlinx.serialization.json.JsonObject
        val found = People.decode(json)
        assertEquals(
            listOf(FoundPerson("u1", "Priya", "priya", priya), FoundPerson("u2", null, null, null)),
            found,
        )
        val contacts = mapOf(priya to "Priya Rao")
        assertEquals("Priya", found[0].title(contacts))
        assertEquals("@priya · In your contacts as Priya Rao", found[0].detail(contacts))
        assertEquals("@pr", FoundPerson("u3", null, "pr", null).title())
        assertEquals("Priya Rao", FoundPerson("u4", null, null, priya).title(contacts))
        assertEquals("Someone on SKIPTI", found[1].title())
        assertNull(found[1].detail())
        assertEquals(emptyList<FoundPerson>(), People.decode(kotlinx.serialization.json.JsonObject(emptyMap())))
    }

    @Test
    fun contacts_become_fingerprints_with_their_names() {
        val byHash = ContactBook.byHash(listOf(
            ContactBook.Entry("Priya Rao", "Priya@Example.com"),
            ContactBook.Entry("Priya (work)", "priya@example.com "),
            ContactBook.Entry("", "bo@example.com"),
            ContactBook.Entry("Broken", "not an email"),
        ))
        assertEquals(2, byHash.size)
        assertEquals("Priya Rao", byHash[priya])
        assertEquals("bo@example.com", byHash[EmailFingerprint.hash("bo@example.com")])
    }

    /** The shape rule and the copy; the list itself is UsernameRulesTest's. */
    @Test
    fun usernames_are_refused_here_as_the_cloud_would() {
        assertNull(People.usernameProblem("@Priya_92"))
        assertEquals(PeopleCopy.USERNAME_SHAPE, People.usernameProblem("pr"))
        assertEquals(PeopleCopy.USERNAME_SHAPE, People.usernameProblem("pri ya"))
        // No Context on the JVM, so the bundled list is not loaded: parse the cloud's own file.
        val list = UsernameRules.parse(
            File(System.getProperty("user.dir")).resolve("../../firebase/functions/blocklist/usernames.json").readText(),
        )
        assertEquals(UsernameRules.Problem.UNAVAILABLE, UsernameRules.problem("Support", list))
    }

    @Test
    fun an_invitation_is_read_only_in_its_own_shape() {
        val token = "A".repeat(43)
        val good = mapOf("groupName" to "Goa trip", "fromName" to "Al", "memberName" to "Priya", "token" to token)
        val item = IncomingInvites.item("i1", good)
        assertEquals("https://skipti.vardrlabs.com/join#$token", item?.link)
        assertEquals("Al", item?.fromName)
        assertNull(IncomingInvites.item("i2", good + ("token" to "short")))
        assertNull(IncomingInvites.item("i3", good + ("groupName" to "")))
        assertTrue(IncomingInvites.item("i4", good - "fromName") != null)
        assertTrue(PushRoute.isInvite { if (it == "kind") "invite" else null })
        assertFalse(PushRoute.isInvite { if (it == "kind") "record" else null })
        assertNull(PushRoute.target { if (it == "kind") "invite" else null })
    }

    /** D-208: an invitation to a group already on this phone is not offered
     *  (its Join could only say "already used"); the others are, and so is an
     *  invitation in the older shape that names no group. */
    @Test
    fun an_invitation_to_a_group_you_are_in_is_not_offered() {
        val token = "A".repeat(43)
        val goa = "c".repeat(32)
        val flat = "f".repeat(32)
        val shape = mapOf("groupName" to "Goa trip", "memberName" to "Priya", "token" to token)
        val items = listOfNotNull(
            IncomingInvites.item("a", shape + ("groupId" to goa)),
            IncomingInvites.item("b", shape + ("groupId" to flat)),
            IncomingInvites.item("old", shape),
        )
        assertEquals(goa, items.first().groupId)
        assertEquals(listOf("b", "old"), IncomingInvites.waiting(items, joined = setOf(goa)).map { it.id })
        assertEquals(listOf("a", "b", "old"), IncomingInvites.waiting(items, joined = emptySet()).map { it.id })
    }

    @Test
    fun the_people_words_match_ios() {
        assertEquals("Let people find me by my username or email", PeopleCopy.FINDABLE_TOGGLE)
        assertEquals("People who know your username or email can find you and invite you to their groups.", PeopleCopy.FINDABLE_ON)
        assertEquals("Nobody can find you. You can still join a group with an invite link.", PeopleCopy.FINDABLE_OFF)
        assertEquals("3 to 20 letters, numbers or _. People can find you by it.", PeopleCopy.USERNAME_HELP)
        assertEquals(
            "Each email in your contacts is turned into a fingerprint on this phone. SKIPTI's cloud compares the fingerprints and keeps none of them.",
            PeopleCopy.CONTACTS_DETAIL,
        )
        assertEquals("Nobody on SKIPTI matches. They may have switched off being found; an invite link always works.", PeopleCopy.NO_MATCH)
        assertEquals("Invited. Priya gets a notification and the invite in SKIPTI.", PeopleCopy.invited("Priya"))
        assertEquals("Al invited you to Goa trip", PeopleCopy.invitedYou("Al", "Goa trip"))
        assertEquals("You are invited to Goa trip", PeopleCopy.invitedYou(null, "Goa trip"))
        assertEquals("Find Priya on SKIPTI", PeopleCopy.findOnSkipti("Priya"))
        assertEquals("Type a username (3 to 20 letters, numbers or _) or a whole email address.", PeopleCopy.NOT_A_QUERY)
    }

    // ---- D-198: your phones and deleting your account ----

    @Test
    fun phones_list_this_phone_first_then_the_most_recently_seen() {
        val thisPhone = "a".repeat(32)
        val rows = Phones.rows(listOf(
            "p1" to mapOf("name" to "Pixel 8", "platform" to "android", "lastSeen" to com.google.firebase.Timestamp(100, 0)),
            thisPhone to mapOf("name" to "iPhone", "platform" to "ios", "lastSeen" to com.google.firebase.Timestamp(50, 0)),
            "p2" to mapOf("name" to "", "platform" to "ios", "lastSeen" to com.google.firebase.Timestamp(200, 0), "signedOut" to true),
        ), thisPhone)
        assertEquals(listOf(thisPhone, "p2", "p1"), rows.map { it.id })
        assertEquals(listOf("iPhone", "iPhone", "Pixel 8"), rows.map { it.name })
        assertEquals(listOf(true, false, false), rows.map { it.isThis })
        assertTrue(rows[1].signedOut)
        assertEquals("This phone", Phones.status(rows[0]) { "x" })
        assertEquals("Signed out", Phones.status(rows[1]) { "x" })
        assertEquals("Last seen 100000", Phones.status(rows[2]) { "$it" })
        assertEquals("", Phones.platformWord("web"))
    }

    /** "Signed out from another phone" only when another phone asked: this
     *  phone's own Sign out everywhere or deletion request signs it out quietly. */
    @Test
    fun the_remote_notice_is_only_for_another_phones_request() = kotlinx.coroutines.runBlocking {
        RemoteSignOut.notice = false
        val before = RemoteSignOut.signOuts
        var signOuts = 0
        RemoteSignOut.arrived(false) { signOuts += 1 }
        assertEquals("no account, nothing to sign out", 0, signOuts)
        assertFalse(RemoteSignOut.notice)
        assertEquals("open screens are told only of a real sign-out", before, RemoteSignOut.signOuts)
        RemoteSignOut.arrived(true) { signOuts += 1 }
        assertEquals(1, signOuts)
        assertTrue("another phone asked", RemoteSignOut.notice)
        assertEquals(before + 1, RemoteSignOut.signOuts)
        RemoteSignOut.notice = false
        RemoteSignOut.quietly { RemoteSignOut.arrived(true) { signOuts += 1 } }
        assertEquals("still signed out", 2, signOuts)
        assertFalse("this phone asked", RemoteSignOut.notice)
        assertEquals("its own sign-out still closes the account's screens", before + 2, RemoteSignOut.signOuts)
        assertFalse(RemoteSignOut.ownRequest)
        runCatching { RemoteSignOut.quietly { throw IllegalStateException("refused") } }
        assertFalse("a refused request ends too", RemoteSignOut.ownRequest)
    }

    @Test
    fun only_a_sign_out_since_this_sign_in_counts() {
        val signedInAt = 1_790_000_000_000L
        fun flag(ms: Long?): Map<String, Any?> =
            if (ms == null) mapOf("signedOut" to true) else mapOf("signedOut" to true, "signedOutAt" to CloudRecords.timestamp(ms))
        assertTrue(Phones.signedOutSince(flag(signedInAt + 1), signedInAt))
        assertTrue(Phones.signedOutSince(flag(signedInAt), signedInAt))
        assertFalse(Phones.signedOutSince(flag(signedInAt - 1), signedInAt))
        assertTrue("no time: take it as now", Phones.signedOutSince(flag(null), signedInAt))
        assertFalse(Phones.signedOutSince(mapOf("signedOut" to false), signedInAt))
        assertFalse(Phones.signedOutSince(null, signedInAt))
        assertTrue(PushRoute.isDevice { if (it == "kind") "device" else null })
        assertFalse(PushRoute.isDevice { if (it == "kind") "invite" else null })
    }

    @Test
    fun the_phones_words_match_ios() {
        assertEquals("Your phones", PhonesCopy.TITLE)
        assertEquals("It stops syncing and signs itself out the next time it goes online. Its ledger stays on it.", PhonesCopy.CONFIRM_BODY)
        assertEquals(
            "Every phone on your account, this one too, is signed out. Each keeps its ledger. Use this if a phone is lost or stolen.",
            PhonesCopy.EVERYWHERE_BODY,
        )
        assertEquals("This phone was signed out of your account from another phone. Your ledger stays here.", PhonesCopy.REMOTE_BODY)
        assertEquals(
            "Deleting your account removes it from SKIPTI's cloud: your profile, username, phones and personal records. You leave every group; what you added to a group stays for its other members. Groups where you are the only member are deleted.",
            PhonesCopy.DELETE_INTRO,
        )
        assertEquals(
            "Deletion happens 7 days after you ask. To keep your account, sign in before then and tap Keep my account. After that it cannot be undone.",
            PhonesCopy.DELETE_GRACE,
        )
        assertEquals(
            "Your account will be deleted on 9 October 2026. To keep it, sign in before then and tap Keep my account.",
            PhonesCopy.deleteDone("9 October 2026"),
        )
        assertEquals("Keep my account", PhonesCopy.KEEP)
    }
}
