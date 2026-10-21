package com.vardrlabs.skipti

import uniffi.skipti_core.SkiptiVault

/**
 * The serialisation gate around SkiptiVault.open: iOS's VaultGate ported
 * one-to-one (D-60). skrin's open runs the schema migration AND the
 * database-key provisioning window; neither interleaves safely with itself
 * (two concurrent fresh opens can both run schema_v1.sql and the loser dies,
 * or two keys get provisioned and one vault strand). One process-wide lock
 * across the WHOLE open; after it returns, vault-internal transactions guard
 * live handles. Every app call site MUST go through here.
 */
object VaultGate {
    private val lock = Object()

    fun open(path: String): SkiptiVault = synchronized(lock) {
        SkiptiVault.open(path, AndroidKeystore(appContext()))
    }

    // set by SkiptiApp.onCreate, no global context grab anywhere else.
    @Volatile lateinit var appContextRef: android.content.Context

    private fun appContext(): android.content.Context = appContextRef.applicationContext
}
