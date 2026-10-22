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

/** D-247: the look's states on top of the groups proof (`--groups-proof --settle-look <step>`, a proof's argument, so
 *  debuggable builds only: ProofArgs): `you` records every one of your Goa trip and Flat 4B settles (Goa all settled
 *  for you; in Flat 4B you are square, Kiran still owes Meera); `partial` holds 1,200.00 of the 3,000.00 Sam owes you
 *  (the toast with Undo, the remainder); `undo` holds it and takes it back ("Not recorded."); `sheet` and `toomuch`
 *  open Paris's "Another amount" (the second typed over the debt); `ring` and `ring-nolock` show You (the second as a
 *  phone with no lock); `group` opens Flat 4B's page. The twin of iOS's `SettleLook`. */
object SettleLook {
    /** Set by the groups proof from its arguments; null for every ordinary launch. */
    @Volatile var step: String? = null

    fun parse(args: List<String>): String? {
        val i = args.indexOf("--settle-look")
        return if (i >= 0) args.getOrNull(i + 1) else null
    }

    /** The step's ledger state, once, on an untouched proof ledger; returns Flat 4B's id (for `group`). The hold runs on
     *  the main thread, as the app's own settles do. */
    suspend fun apply(vaultPath: String): String? {
        val step = step ?: return null
        val store = LedgerStore(vaultPath)
        val (fresh, sections) = withContext(Dispatchers.IO) {
            store.loadSettlements().isEmpty() to SettleAll.sections(store)
        }
        val flat = sections.firstOrNull { it.named.group.name == "Flat 4B" }?.named?.group?.id
        if (!fresh) return flat
        when (step) {
            "you" -> withContext(Dispatchers.IO) {
                for (s in sections.filter { it.named.group.name in listOf("Goa trip", "Flat 4B") }) {
                    for (line in s.lines) for (e in line.entries) SettleAll.append(store, e, line.currencyCode, s.named)
                }
            }
            "partial", "undo" -> {
                val s = sections.firstOrNull { it.named.group.name == "Goa trip" } ?: return flat
                val line = s.lines.firstOrNull() ?: return flat
                val sam = line.entries.firstOrNull { LedgerStore.label(it.id, s.named) == "Sam" } ?: return flat
                SettleHold.shared.hold(SettleAll.settlement(sam, line.currencyCode, s.named, 120_000), "Sam", LedgerStore(vaultPath))
                if (step == "undo") SettleHold.shared.pending?.let { SettleHold.shared.undo(it.id) }
            }
        }
        return flat
    }

    private var opened = false

    /** `sheet` / `toomuch`: the card whose sheet opens, once. */
    fun draft(sections: List<SettleSection>): OtherAmountDraft? {
        if (step != "sheet" && step != "toomuch" || opened) return null
        val s = sections.firstOrNull { it.named.group.name == "Paris" } ?: return null
        val line = s.lines.firstOrNull() ?: return null
        val e = line.entries.firstOrNull() ?: return null
        opened = true
        return OtherAmountDraft(SettleKey(s.named.group.id, line.currencyCode, e), e, line.currencyCode, s.named, "Ana", s.history)
    }

    /** `toomuch`: what the field holds instead of the debt. */
    fun typed(): String? = if (step == "toomuch") "40.00" else null

    /** `ring-nolock`: You reads as on a phone with no lock. */
    fun noLock(): Boolean = step == "ring-nolock"
}
