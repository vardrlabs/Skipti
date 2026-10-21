# C1: Adding people, with a username lookup and recents (D-207) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A group's People screen gets an "Add a person" sheet: a name, an optional @username looked up and previewed before adding, "Add and invite" in one tap, and recents (people from your other groups). Approved mock-up: https://claude.ai/artifact/9rN75e4BCcew1GHWaWdFUQ. Spec: `docs/superpowers/specs/2026-10-13-feedback-round-design.md`, "C".

**Architecture:** One pure rule, `Recents.people(...)`, twinned and tested, computes the recents from data already on the phone (named groups and their claims). The sheet reuses `People.find(.username)` (findPeople) and `People.invite` (inviteUser) for a looked-up person. A recent person who joined a group with you is invited through a new callable, `inviteKnown`, which finds their account by participant id and allows it only when the caller and the target are both members of the group they share; it never returns a uid.

**Tech Stack:** SwiftUI, Compose, Cloud Functions (plain JS), node:test against the emulators.

## Global Constraints

- GUARANTEES #5: without an account the username field, "On SKIPTI" chips and inviting are ABSENT; names (and recents as names) still work.
- Labels (twins): "Add a person", "Name", "Their name", "Username (optional)", "find them on SKIPTI", "Add and invite", "Add only", "Recently with you", "On SKIPTI", "Nobody on SKIPTI is @%@." + " Check the spelling, or add the name and send an invite link later.", "Added and invited".
- Usernames shown with `Text(verbatim:)` (autolink gate leg).
- The inline "Add a person by name" field on PeopleScreen is replaced by the sheet; New group keeps its rows (C1 covers existing groups; recents there are a later nicety, recorded).
- Cloud: `inviteKnown` gets refusal tests in `firebase/tests/functions.test.mjs` (unauthenticated, caller not in the via group, target not in the via group, unknown participant, target already in the destination group, bad shapes) and one success; daily `invites` limit charged as inviteUser does. Deploy only with the founder's yes; CLOUD-SECURITY row the same day.
- Asserted-anchor edits, sabotage each new guard, look on both shells, full gate, BUILD-LOG, branch `d207-add-people`, merge `--no-ff`, `[skip ci]`, no attribution.

---

### Task 1: Recents, twinned, tests first

**Produces (Swift, in People.swift):**
```swift
struct RecentPerson: Equatable, Identifiable {
    let name: String
    let groups: [String]          // the other groups' names, at most 2, most shared first
    let participantId: String?    // set when they joined one of those groups (a claim): "On SKIPTI"
    let viaGroupId: String?       // a group you share with them, for inviteKnown
    var id: String { participantId ?? "name:" + name.lowercased() }
}
enum Recents {
    /// People from your other groups, for adding to `groupId`: you and anyone
    /// already in `groupId` (same participant, or same name ignoring case and
    /// spaces) are left out; one entry per participant, else per name; most
    /// shared groups first, then by name; at most `limit`.
    static func people(groups: [NamedGroupWire], claims: [String: [ClaimOut]], groupId: String, limit: Int = 8) -> [RecentPerson]
}
```
**Kotlin twin** (People.kt): `data class RecentPerson(...)`, `object Recents { fun people(groups: List<NamedGroupWire>, claims: Map<String, List<ClaimOut>>, groupId: String, limit: Int = 8): List<RecentPerson> }`.

Tests (same cases on both shells, fixtures built by hand): three groups (Goa: you, Rahul (claimed p1), Meera;
Flat: you, Rahul (claimed p1), Sam (claimed p2); Paris: you, Ana, " rahul " (unclaimed)) and a fourth, "Trip" (you
only), being added to. Expected, in order: Rahul (p1; groups Goa, Flat, via Goa; the unclaimed " rahul " in Paris
merges into him by name, so his count is 3), Sam (p2, Flat), Ana (Paris), Meera (Goa) (ties by name). Negatives:
adding to Goa leaves out Rahul and Meera (already there); no group's self member ever appears; limit 2 keeps Rahul
and Sam.

### Task 2: `inviteKnown` (cloud)

`firebase/functions/index.js`: `export const inviteKnown = onCall(CALLABLE, ...)`: `signedIn`; `inviteFields(data)` (as inviteUser); `viaGroupId` HEX32, `participantId` HEX32; caller in `groups/{viaGroupId}.memberUids` else permission-denied; `users.where('participantId','==',participantId).limit(2)` exactly one doc else not-found; target uid in via group's memberUids else not-found; target != caller; destination group: caller is a member (memberGroup) and target not already a member (failed-precondition); `charge(uid,'invites')`; `makeInvite`; write `users/{target}/invites`; push as inviteUser; `report('inviteKnown', …)`. Shared code with inviteUser factored into `deliverInvite(fields, uid, target)`.
Tests in `functions.test.mjs` per Global Constraints.

### Task 3: iOS sheet

`AddPersonSheet(store:group:onAdded:)` in People.swift: name field; when signed in, the @username field (`.onSubmit` looks up via `People.find(.username)`), found card (name, @username; Add and invite / Add only), missing note; recents list (tap: add the name; when participantId and signed in: add + `People.inviteKnown`). Adding uses `store.addMember(groupID:name:)` then, for invites, the new member's id. PeopleScreen: the inline field becomes a SecondaryButtonStyle "Add a person" opening the sheet. A small done bar after each add ("Added and invited" / "Added").

### Task 4: Android sheet

The twin: `AddPersonSheet` (ModalBottomSheet) in People.kt; PeopleScreen in GroupScreens.kt gets the button.

### Task 5: Sabotage, look, gate, deploy, record, merge

- Sabotage: Recents keeps your own entry (iOS, Kotlin); inviteKnown skips the via-group membership check of the TARGET (the emulator test "target not in the via group" goes red).
- Look: both shells, signed out (no username field, recents as names) and signed in on the emulators (`--emulators`, a looked-up user found; a recent invited).
- Full gate; deploy `functions:inviteKnown` with the founder's yes; CLOUD-SECURITY row; BUILD-LOG D-207; merge.
