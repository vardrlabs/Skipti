package com.vardrlabs.skipti

import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * D-193 (the Firebase plan's unit 3): this account's profile and this phone's
 * entry in the cloud, written at sign-in and when SKIPTI comes to the front;
 * iOS's twin is CloudProfile.swift. `users/{uid}` holds only the account's
 * "you" id; `users/{uid}/devices/{id}` names this phone for the account's
 * phone list (unit 8). What each document may hold is fixed by
 * `firebase/firestore.rules`, and the tests compare these field lists with
 * the rules' own.
 */
object CloudProfile {
    // This phone's id in the cloud is its records' device id (one id per
    // phone, as on iOS): 16 random bytes from core, made once and kept in the
    // vault, so a reinstall is a new phone. (Corrected D-196: D-194 recorded
    // this for both shells, but Android still minted a second id under
    // `device/id`; nothing read it besides this file and the proof.)

    /** Exactly the keys the rules allow, in the rules' order. */
    val PROFILE_FIELDS = listOf("participantId", "createdAt", "updatedAt", "name")
    val DEVICE_CREATE_FIELDS = listOf("name", "platform", "appVersion", "lastSeen", "fcmToken")
    val DEVICE_UPDATE_FIELDS = listOf("name", "appVersion", "lastSeen", "fcmToken")

    sealed class Outcome {
        data object Registered : Outcome()
        data class Failed(val reason: String) : Outcome()
        /** D-198: this phone was signed out from elsewhere since it signed in. */
        data object SignedOutRemotely : Outcome()
    }

    fun deviceId(store: LedgerStore): String = LedgerStore.Sync.deviceId(store)

    /** D-197: `name` is the name people see when they find you (your name in
     *  SKIPTI), sent when you have one. */
    fun profileCreate(participantId: String, name: String? = null): Map<String, Any> = mapOf(
        "participantId" to participantId,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    ) + (cloudName(name)?.let { mapOf("name" to it) } ?: emptyMap())

    fun profileUpdate(name: String? = null): Map<String, Any> =
        mapOf("updatedAt" to FieldValue.serverTimestamp()) + (cloudName(name)?.let { mapOf("name" to it) } ?: emptyMap())

    /** The rules' limit: 1-60 characters, as the rules' `string.size()` counts
     *  them. Counted here in UTF-16 code units (never fewer than the rules
     *  count), cut at a whole character, so the cut name always passes. */
    fun cloudName(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length <= NAME_MAX) return trimmed
        val characters = java.text.BreakIterator.getCharacterInstance()
        characters.setText(trimmed)
        var end = 0
        var next = characters.next()
        while (next != java.text.BreakIterator.DONE && next <= NAME_MAX) {
            end = next
            next = characters.next()
        }
        return trimmed.substring(0, end).takeIf { it.isNotEmpty() }
    }

    const val NAME_MAX = 60

    /** `users/{uid}` does not exist yet: sign-in's registration makes it. */
    class NoProfileException : Exception("no profile yet")

    /**
     * D-211: the account's public name (`users/{uid}.name`, what find-people
     * shows and what `shareProfile` checks every record's name against) made
     * the name on this phone. Saving a name used to reach it only when
     * registration next ran; sharing a profile needs it now. Reads the server's
     * copy, writes only when it differs, and throws when the cloud cannot be
     * reached or the profile is not there yet. Never creates the document.
     */
    suspend fun ensureName(uid: String, name: String) {
        val wanted = cloudName(name) ?: return
        val user = Cloud.db().collection("users").document(uid)
        val snapshot = user.get(Source.SERVER).awaitTask()
        if (!snapshot.exists()) throw NoProfileException()
        if (snapshot.getString("name") != wanted) user.update(profileUpdate(wanted)).awaitTask()
    }

    fun deviceCreate(name: String, appVersion: String): Map<String, Any> = mapOf(
        "name" to name, "platform" to "android", "appVersion" to appVersion,
        "lastSeen" to FieldValue.serverTimestamp(),
    )

    fun deviceUpdate(name: String, appVersion: String): Map<String, Any> = mapOf(
        "name" to name, "appVersion" to appVersion, "lastSeen" to FieldValue.serverTimestamp(),
    )

    /** D-196: this phone's push address (FCM's token), or its removal. The
     *  rules want `lastSeen` refreshed with any change. */
    fun tokenUpdate(token: String?): Map<String, Any> = mapOf(
        "fcmToken" to (token ?: FieldValue.delete()), "lastSeen" to FieldValue.serverTimestamp(),
    )

    /** "Pixel 8": the model, as the phone's settings name it. */
    fun deviceName(): String = (Build.MODEL ?: "Android phone").trim().take(60).ifEmpty { "Android phone" }

    /** Creates the profile and this phone's entry, or refreshes them. A
     *  failure (offline) is retried the next time SKIPTI comes to the front.
     *  Call off the main thread. */
    suspend fun register(uid: String, store: LedgerStore, appVersion: String): Outcome = try {
        val me = store.ensureSelf()
        val participantId = me.id
        val device = deviceId(store)
        val user = Cloud.db().collection("users").document(uid)
        if (user.get(Source.SERVER).awaitTask().exists()) {
            user.update(profileUpdate(me.name)).awaitTask()
        } else {
            user.set(profileCreate(participantId, me.name)).awaitTask()
        }
        val phone = user.collection("devices").document(device)
        val version = appVersion.take(20).ifEmpty { "0" }
        val existing = phone.get(Source.SERVER).awaitTask()
        // D-198: a phone signed out from elsewhere signs itself out; a flag older
        // than this sign-in is from before it signed in again, and its entry is
        // made fresh (no phone can clear the flag itself).
        if (existing.exists() && existing.getBoolean("signedOut") == true) {
            if (Phones.signedOutSince(existing.data, Phones.authTimeMs())) return Outcome.SignedOutRemotely
            phone.delete().awaitTask()
            phone.set(deviceCreate(deviceName(), version)).awaitTask()
        } else if (existing.exists()) {
            phone.update(deviceUpdate(deviceName(), version)).awaitTask()
        } else {
            phone.set(deviceCreate(deviceName(), version)).awaitTask()
        }
        Outcome.Registered
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: "The cloud could not be reached.")
    }
}

/**
 * The one Firestore instance. Its cache lives in memory only: the vault
 * (SQLCipher) is the phone's database, and a disk cache would keep cloud
 * documents outside it, unencrypted by SKIPTI.
 */
object Cloud {
    /** Set by the emulator proof before the first use of [db]. */
    @Volatile var emulator = false

    private val instance: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().also { db ->
            if (emulator) db.useEmulator("localhost", 8180)
            db.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
    }

    fun db(): FirebaseFirestore = instance
}

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    // D-235 (the Android audit's doubt 4): a cancelled Task resumed nothing, and its coroutine (CloudSync's one serial
    // lane among them) waited for good.
    addOnCanceledListener { cont.cancel() }
}
