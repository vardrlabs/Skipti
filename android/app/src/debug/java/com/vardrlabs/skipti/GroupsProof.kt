package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.NamedGroupWire

// D-260 (G-13, decision 269): a proof surface moved out of src/main, Debug builds only (src/release has none).

/**
 * `--groups-proof` (D-179, headless verification, not a user flow): on an
 * empty ledger, gives you a name and makes three groups (the canvas's Goa
 * trip, Flat 4B and Paris), then shows the ordinary tabs on Groups. iOS's
 * twin is `GroupsProofScreen`.
 */
@Composable
fun GroupsProof(vaultPath: String, tabs: @Composable () -> Unit) {
    var ready by remember { mutableStateOf<Result<Unit>?>(null) }
    LaunchedEffect(Unit) {
        ready = withContext(Dispatchers.IO) { runCatching { GroupsProofSeed.seed(LedgerStore(vaultPath)) } }
    }
    val result = ready ?: return
    result.exceptionOrNull()?.let {
        Text("groups proof failed: ${it.message}", modifier = Modifier.padding(20.dp))
        return
    }
    tabs()
}

object GroupsProofSeed {
    /** Only when there is no group yet: a proof never adds to a real ledger. */
    fun seed(store: LedgerStore) {
        if (store.ensureSelf().name == null) store.setYourName("Prithvi")
        if (store.namedGroups().isNotEmpty()) return
        store.createGroup("Goa trip", "INR", listOf("Rahul", "Meera", "Sam"))
        store.createGroup("Flat 4B", "INR", listOf("Meera", "Kiran"))
        store.createGroup("Paris", "EUR", listOf("Ana"))
        // D-180: a few expenses, so Balance and a group's page have numbers.
        val groups = store.namedGroups()
        val tree = uniffi.skipti_core.ffiCategoryTreeDefault()
        fun leaf(parent: String, name: String): String? {
            val p = tree.categories.firstOrNull { it.parent == null && it.name == parent }
            return tree.categories.firstOrNull { it.parent == p?.id && it.name == name }?.id
        }
        val now = System.currentTimeMillis()
        fun add(group: String, payer: Int, minor: Long, code: String, note: String, category: String?, hoursAgo: Long) {
            val g = groups.firstOrNull { it.group.name == group } ?: return
            val ids = g.members.map { it.id }
            val each = minor / ids.size
            store.appendExpense(
                ExpenseDTO(
                    payer = ids[payer], paidMinor = each * ids.size, currencyCode = code, group = g.group.id,
                    shares = ids.map { ExpenseDTO.ShareDTO(it, each) }, note = note,
                    // D-189: added by whoever paid, so Activity has something to show.
                    timestampMs = now - hoursAgo * 3_600_000, category = category, addedBy = ids[payer],
                ),
            )
        }
        add("Goa trip", 0, 1_200_000, "INR", "Villa, 2 nights", leaf("Stay", "Rentals"), 30)
        add("Goa trip", 1, 480_000, "INR", "Dinner at Britto's", leaf("Food & Drink", "Restaurants"), 3)
        add("Goa trip", 2, 160_000, "INR", "Scooters", leaf("Travel", "Rentals"), 2)
        add("Flat 4B", 0, 270_000, "INR", "Electricity", leaf("Bills & Utilities", "Electricity"), 26)
        add("Flat 4B", 1, 135_000, "INR", "Groceries", leaf("Food & Drink", "Groceries"), 20)
        add("Paris", 1, 6_400, "EUR", "Museum passes", leaf("Entertainment", "Events"), 300)
    }
}
