package com.vardrlabs.skipti

// D-260 (G-13, decision 269): a release build carries no settle look (`--settle-look`, a look's tool on the groups
// proof). It lives in `src/debug` only; this stub keeps the three ordinary callers compiling (SettleAll's sheet and its
// field, You's ring) and answers as an ordinary launch does: no draft, nothing typed, the phone's own lock.

object SettleLook {
    @Suppress("UNUSED_PARAMETER")
    fun draft(sections: List<SettleSection>): OtherAmountDraft? = null

    fun typed(): String? = null

    fun noLock(): Boolean = false
}
