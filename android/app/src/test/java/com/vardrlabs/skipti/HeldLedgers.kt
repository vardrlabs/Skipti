package com.vardrlabs.skipti

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * D-249 fix wave (decision 208): every expense write checks that its people are the ledger's people as the vault holds
 * them ([LedgerStore.ledgerMembers]). A test that writes into a fixed group id, or a Just me expense under a fixed "you",
 * first makes the vault hold that group or that "you", as a phone that made or joined the group would.
 */
object HeldLedgers {
    private val json = Json { encodeDefaults = false }

    /** The kv group [id] with [members] (added to any groups already held). */
    fun group(store: LedgerStore, id: String, members: List<String>, currency: String = "INR") {
        val held = store.loadGroups().filter { it.id != id } + GroupDTO(id, "Test", currency, members)
        store.rawKvSet(LedgerStore.GROUPS_KEY, json.encodeToString(ListSerializer(GroupDTO.serializer()), held).toByteArray())
    }

    /** This vault's "you" is [id]. */
    fun self(store: LedgerStore, id: String) {
        store.rawKvSet(LedgerStore.SELF_KEY, json.encodeToString(SelfDTO.serializer(), SelfDTO(id)).toByteArray())
    }
}
