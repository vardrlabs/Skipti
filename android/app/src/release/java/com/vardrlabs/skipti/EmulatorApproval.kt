package com.vardrlabs.skipti

// D-254: a release build carries no emulator approval. The helper lives in `src/debug` only; this stub keeps the
// proofs' calls (Account.kt, AppRoot.kt's GroupEndProof) compiling and approves nothing, with no network call (the proofs
// are never reached in a release build: ProofArgs answers null there). ApprovalTest holds this file to no call.

object EmulatorApproval {
    @Suppress("UNUSED_PARAMETER")
    suspend fun approve(uid: String): Boolean = false
}
