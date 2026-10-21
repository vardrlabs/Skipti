package com.vardrlabs.skipti

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable

// D-251 review (M3): a release build carries no store-shots seed. The seed lives in `src/debug` only; this stub keeps
// MainActivity's two calls compiling and does nothing: the args pass through untouched (ProofArgs honours none of them
// in a release build), and the proof's surface draws nothing (it is never reached: ProofArgs.proof answers null).
// StoreShotsTest holds this file to no vault, no preference and no seed.

object StoreShots {
    @Suppress("UNUSED_PARAMETER")
    fun prepare(context: Context, intent: Intent?, restored: Boolean, debuggable: Boolean): String? =
        intent?.getStringExtra("args")
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun StoreShotsProof(vaultPath: String, shell: @Composable () -> Unit) {
}
