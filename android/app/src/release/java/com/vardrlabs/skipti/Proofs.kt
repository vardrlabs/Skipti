package com.vardrlabs.skipti

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

// D-260 (G-13, decision 269): a release build carries no proof surface. The dispatch, the emulator launch hook and the
// proof screens live in `src/debug` only; these stubs keep AppRoot's two calls compiling, draw nothing and do nothing (they
// are never reached with a proof: ProofArgs answers null and false in a release build). scripts/check-release-proofs.py
// holds src/main and src/release to none of the proof words or names.

@Suppress("UNUSED_PARAMETER")
@Composable
fun ProofSurface(headless: String?, args: List<String>, vaultPath: String): Boolean = false

object ProofLaunch {
    @Suppress("UNUSED_PARAMETER")
    fun emulators(activity: ComponentActivity, launchArgs: String?, debuggable: Boolean, openGroup: (String) -> Unit) {
    }
}
