package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * D-191: the account is a Firebase Auth user; iOS's twin is Account.swift.
 * Every "is this phone signed in" question reads [isSignedIn]; Firebase keeps
 * and refreshes the session itself.
 *
 * D-168's rule carries over: one account per phone. The phone remembers the
 * first Firebase uid that signed in on it (kept through sign-out), and a
 * sign-in by another uid is signed straight back out and refused. Only the
 * Firebase key counts: an owner from the old relay ([AccountOwner.KEY])
 * belongs to an account that no longer exists and blocks nothing.
 */
object Account {
    const val OWNER_KEY = "account/firebase-uid"

    sealed class Outcome {
        data object SignedIn : Outcome()
        /** Another account's ledger is on this phone: nothing was kept. */
        data object OtherAccount : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private fun auth(): FirebaseAuth = FirebaseAuth.getInstance()

    fun isSignedIn(): Boolean = auth().currentUser != null

    fun uid(): String? = auth().currentUser?.uid

    /** The pure rule: no owner yet, or the same account. */
    fun owns(stored: String?, incoming: String): Boolean = stored == null || stored == incoming

    /** The vault's owner, null when none. For the cloud gates, which fail closed: a vault that cannot be read reads as
     *  no owner, so nothing syncs. Never for a decision that would sign someone out or write the owner ([readOwner]). */
    fun storedOwner(store: LedgerStore): String? =
        runCatching { readOwner(store) }.getOrNull()

    /** D-235 final wave (iOS's M4 `readOwner`): the vault's owner, throwing when the vault cannot be read (CLAUDE.md
     *  10.1: a swallowed vault read turns "cannot read" into "empty"). */
    fun readOwner(store: LedgerStore): String? =
        store.rawKvGet(OWNER_KEY)?.takeIf { it.isNotEmpty() }?.decodeToString()

    /** How this phone signed in (D-172), for Settings. */
    fun method(store: LedgerStore): SignInMethod? = if (isSignedIn()) SignInMethod.stored(store) else null

    suspend fun signIn(credential: AuthCredential, method: SignInMethod, store: LedgerStore): Outcome {
        val incoming = try {
            suspendCancellableCoroutine<String> { cont ->
                auth().signInWithCredential(credential)
                    .addOnSuccessListener { result ->
                        val uid = result.user?.uid
                        if (uid != null) cont.resume(uid) else cont.resumeWithException(IllegalStateException("no user"))
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: "Sign in did not finish.")
        }
        val kept = keep(incoming, method, store) { signOut() }
        if (kept != Outcome.SignedIn) return kept
        // D-254: ask for approval (the person's own name, this platform) and listen for the decision, before the cloud
        // starts: it starts only for an approved account (`CloudSync.ready`), at once when approval arrives.
        Approval.signedIn(incoming, store)
        return try {
            // D-193/D-194: register this phone, upload the ledger, start listening.
            CloudSync.appIsActive(true)
            Outcome.SignedIn
        } catch (e: Exception) {
            signOut()
            Outcome.Failed(SignedInCopy.NOT_SAVED)
        }
    }

    /**
     * After Firebase signed [incoming] in: the vault keeps its owner, or the sign-in is refused and [signOut] runs.
     * D-235 final wave (iOS's M4): a vault that cannot be read is a failed sign-in, never "no owner yet", which would
     * write this account over the real owner. The vault is left as it is.
     */
    internal fun keep(incoming: String, method: SignInMethod, store: LedgerStore, signOut: () -> Unit): Outcome {
        val owner = try {
            readOwner(store)
        } catch (e: Exception) {
            signOut()
            return Outcome.Failed(SignedInCopy.NOT_SAVED)
        }
        if (!owns(owner, incoming)) {
            signOut()
            return Outcome.OtherAccount
        }
        return try {
            if (owner == null) store.rawKvSet(OWNER_KEY, incoming.toByteArray(Charsets.UTF_8))
            SignInMethod.remember(method, store)
            Outcome.SignedIn
        } catch (e: Exception) {
            // The vault could not keep it: not signed in, rather than signed
            // in to a phone that does not know whose it is.
            signOut()
            Outcome.Failed(SignedInCopy.NOT_SAVED)
        }
    }

    fun signOut() {
        val left = runCatching { uid() }.getOrNull()
        auth().signOut()
        // D-244 (M5): a later sign-in catches up with the account again.
        SettingsSync.signedOut(left)
        // D-254: the approval listener stops and the vault forgets the state (sign-out everywhere, a deletion and a
        // sign-out from another phone all come through here).
        Approval.signedOut()
        CloudSync.accountSignedOut()
    }
}
