package com.vardrlabs.skipti

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * D-254 (debug builds only: this file is in `src/debug`, and release compiles a stub that approves nothing; the emulator
 * proofs' tool, never a user flow): every `--*-emulator-proof` signs in fake accounts, which would now wait forever.
 * This approves one through the LOCAL Functions emulator's `adminApprovals({ action: 'decide', uid, state: 'approved' })`,
 * as the emulators' admin account (`emulator-admin`, `firebase/functions/.env.local`; the production admin id is never
 * that) with an unsigned test token the emulator accepts (the shape `firebase/tests/functions.test.mjs`'s `idToken`
 * builds). Only under [Cloud.emulator]: anywhere else it returns false without a call. Then the phone asks again, so it
 * hears the answer at once. iOS's twin is `EmulatorApproval` in Approval.swift (`#if DEBUG`).
 */
object EmulatorApproval {
    const val ADMIN = "emulator-admin"

    /** An unsigned ID token for the emulators' admin (alg none, signed in with Google). */
    fun adminToken(nowMs: Long = System.currentTimeMillis()): String {
        val issued = nowMs / 1000
        fun part(json: JsonObject): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        val claims = buildJsonObject {
            put("iss", "https://securetoken.google.com/${FirebaseConfig.PROJECT_ID}")
            put("aud", FirebaseConfig.PROJECT_ID)
            put("iat", issued)
            put("exp", issued + 3600)
            put("auth_time", issued)
            put("sub", ADMIN)
            put("user_id", ADMIN)
            putJsonObject("firebase") {
                put("sign_in_provider", "google.com")
                putJsonObject("identities") {}
            }
        }
        return part(buildJsonObject { put("alg", "none"); put("typ", "JWT") }) + "." + part(claims) + "."
    }

    suspend fun approve(uid: String): Boolean {
        if (!Cloud.emulator) return false
        val body = JsonObject(
            mapOf(
                "data" to JsonObject(
                    mapOf("action" to JsonPrimitive("decide"), "uid" to JsonPrimitive(uid), "state" to JsonPrimitive("approved")),
                ),
            ),
        ).toString()
        val ok = runCatching {
            val reply = withContext(Dispatchers.IO) {
                val connection = URL(CloudFunctions.url("adminApprovals")).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${adminToken()}")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            CloudFunctions.decode(reply)
        }.isSuccess
        if (!ok) {
            android.util.Log.w("EMULATOR-APPROVAL", "could not approve $uid")
            return false
        }
        val store = runCatching { VaultGate.appContextRef }.getOrNull()?.let { LedgerStore(CloudSync.vaultPathOf(it)) } ?: return true
        // An ask already in flight may have left before the approval: wait for it, then ask once more.
        Approval.asking(uid, store).await()
        Approval.asking(uid, store).await()
        return true
    }
}
