package com.vardrlabs.skipti

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.ffiDecodeExpenseRevision

/**
 * D-196 (the Firebase plan's unit 6): push notifications. When you add,
 * change or delete an expense in a shared group, your phone writes the line
 * the others will see ("Rahul added Dinner · ₹1,200.00") into the record's
 * `notice`, and the `onGroupRecord` Function sends it to their phones, titled
 * with the group's name. A tap opens the expense. iOS's twin is Push.swift.
 *
 * GUARANTEES #5: nothing here reaches Google without an account. Firebase
 * Messaging's automatic start is off (the manifest), so no token exists and
 * no request is made until the phone is signed in with sync on; signing out
 * or turning sync off deletes the token.
 */
object PushNotice {
    data class Notice(val text: String, val expense: String)

    const val MAX_TITLE = 60
    const val MAX_TEXT = 180
    private const val EXPENSE_TYPE: UByte = 1u

    /** The push line for [record], when it is this phone's own change to an
     *  expense in a group: a new expense, an edit, a delete. Null for anything
     *  else. The amount is core's string. */
    fun notice(
        record: RecordWire,
        thisDevice: String,
        expenses: Map<String, ProjectedExpenseOut>,
        groups: Map<String, NamedGroupWire>,
        locale: LocaleTag,
    ): Notice? {
        val groupId = record.groupId ?: return null
        if (record.authorDeviceId != thisDevice) return null
        val (kind, expenseId) = when (record.recordType) {
            EXPENSE_TYPE -> ActivityFeed.Kind.ADDED to "${record.hlcMillis}:${record.hlcCounter}:${record.hlcDeviceId}"
            LedgerStore.REVISION_TYPE -> {
                val revision = runCatching { ffiDecodeExpenseRevision(record.blob) }.getOrNull() ?: return null
                (if (revision.deleted == true) ActivityFeed.Kind.DELETED else ActivityFeed.Kind.CHANGED) to revision.target
            }
            else -> return null
        }
        if (!PushRoute.isExpenseId(expenseId)) return null
        val expense = expenses[expenseId] ?: return null
        if (expense.expense.group != groupId) return null
        val named = groups[groupId]
        val who = named?.members?.firstOrNull { it.id == named.selfId }?.name ?: ActivityFeed.SOMEONE
        val note = expense.note?.trim().orEmpty()
        val title = when {
            note.isEmpty() -> ActivityFeed.AN_EXPENSE
            note.length > MAX_TITLE -> note.take(MAX_TITLE - 1) + "…"
            else -> note
        }
        var text = ActivityFeed.line(kind, who, title)
        if (kind != ActivityFeed.Kind.DELETED) {
            runCatching {
                uniffi.skipti_core.ffiMoneyFormat(expense.expense.paidMinor, expense.expense.currencyCode, locale)
            }.getOrNull()?.let { text += " · $it" }
        }
        if (text.length > MAX_TEXT) text = text.take(MAX_TEXT - 1) + "…"
        return Notice(text, expenseId)
    }

    /** The notices for the records about to go up, by document id. Reads the
     *  ledger only when one of them could carry a notice. */
    fun notices(records: List<RecordWire>, store: LedgerStore): Map<String, Notice> {
        val device = runCatching { LedgerStore.Sync.deviceId(store) }.getOrNull() ?: return emptyMap()
        val candidates = records.filter {
            it.groupId != null && it.authorDeviceId == device &&
                (it.recordType == EXPENSE_TYPE || it.recordType == LedgerStore.REVISION_TYPE)
        }
        if (candidates.isEmpty()) return emptyMap()
        val projected = runCatching { store.projectedExpenses() }.getOrNull() ?: return emptyMap()
        val expenses = projected.expenses.associateBy { it.id }
        // A group waiting for your name still gets a notice, from "Someone".
        val groups = runCatching { store.namedGroups() }.getOrDefault(emptyList()).associateBy { it.group.id }
        val out = mutableMapOf<String, Notice>()
        for (record in candidates) {
            notice(record, device, expenses, groups, AppLocale.current)?.let { out[CloudRecords.documentId(record)] = it }
        }
        return out
    }
}

/** Where a tapped push goes. Only an expense in a group, named by ids of the
 *  shapes the rules allow: a push's data is read, never trusted. */
object PushRoute {
    data class Target(val groupId: String, val expense: String)

    private val expenseId = Regex("^[0-9]{1,20}:[0-9]{1,10}:[0-9a-f]{32}$")

    fun isExpenseId(value: String): Boolean = expenseId.matches(value)

    fun target(data: (String) -> String?): Target? {
        if (data("kind") != "record") return null
        val group = data("groupId")?.takeIf { CloudRecords.isHex32(it) } ?: return null
        val expense = data("expense")?.takeIf { isExpenseId(it) } ?: return null
        return Target(group, expense)
    }

    fun target(intent: Intent?): Target? = intent?.extras?.let { extras -> target { extras.getString(it) } }

    /** D-197: an invite push (inviteUser) carries only its kind. */
    fun isInvite(data: (String) -> String?): Boolean = data("kind") == "invite"

    fun isInvite(intent: Intent?): Boolean = intent?.extras?.let { extras -> isInvite { extras.getString(it) } } ?: false

    /** D-198: a "Was this you?" push (onDeviceCreated). */
    fun isDevice(data: (String) -> String?): Boolean = data("kind") == "device"

    fun isDevice(intent: Intent?): Boolean = intent?.extras?.let { extras -> isDevice { extras.getString(it) } } ?: false

    /** D-210: a group's own push (a removal request, a decision, a role, a
     *  deletion) names its group and nothing else. */
    fun groupId(data: (String) -> String?): String? {
        if (data("kind") != "group") return null
        return data("groupId")?.takeIf { CloudRecords.isHex32(it) }
    }

    fun groupId(intent: Intent?): String? = intent?.extras?.let { extras -> groupId { extras.getString(it) } }

    fun isGroup(data: (String) -> String?): Boolean = groupId(data) != null

    fun isGroup(intent: Intent?): Boolean = groupId(intent) != null
}

object PushCopy {
    const val TITLE = "Notifications"
    const val ON = "On"
    const val OFF = "Off"
    const val EXPLAIN_ON = "You hear when someone adds, changes or deletes an expense in your groups."
    const val EXPLAIN_ASK = "Hear when someone adds, changes or deletes an expense in your groups."
    const val EXPLAIN_DENIED = "Notifications for SKIPTI are off in your phone's settings."
    const val EXPLAIN_SYNC_OFF = "Notifications come with sync. Turn sync on to hear about your groups."
    const val TURN_ON = "Turn on notifications"
    const val OPEN_SETTINGS = "Open phone settings"
    const val FETCHING = "Bringing in this expense…"
    const val CHANNEL_NAME = "Group activity"
}

/** The phone's side of push: FCM's token in this phone's device document
 *  while signed in with sync on, the permission, and pushes that arrive while
 *  SKIPTI is in front. */
object Push {
    const val CHANNEL_ID = "group-activity"
    private const val PREFS = "push"
    private const val ASKED = "asked"

    /** D-235 (Android audit M1): the account whose device document holds this phone's token, in the vault. Memory
     *  alone forgot it: after a launch that could not register (offline), sign-out and sync off saw no token and left
     *  it in the cloud and valid at FCM, and the phone kept getting the account's group pushes. */
    const val REGISTERED_KEY = "push/registered"

    /** D-235: FCM may still hold a token this phone no longer wants (a delete that could not finish offline); retried
     *  at the next launch that is not signed in with sync on ([settleOwed]). */
    const val DELETE_OWED_KEY = "push/delete-owed"

    /** D-235 (Android audit M2): how long a sign-out or sync off waits on each cloud call before going on. */
    const val CLOUD_TIMEOUT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var token: String? = null
    @Volatile private var savedFor: String? = null

    /** Signed in with sync on (CloudSync): FCM starts and its token is saved
     *  for this phone. The emulator runs (`--emulators`, proofs) never reach
     *  FCM. */
    fun start(store: LedgerStore) {
        if (Cloud.emulator) return
        if (running) {
            saveToken(store)
            return
        }
        running = true
        // D-235: the token is wanted again, so a delete still owed must not take it.
        if (runCatching { mark(store, DELETE_OWED_KEY) != null }.getOrDefault(false)) {
            runCatching { store.rawKvSet(DELETE_OWED_KEY, ByteArray(0)) }
        }
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        messaging.isAutoInitEnabled = true
        messaging.token.addOnSuccessListener { tokenChanged(it, store) }
    }

    /** From the service, or the first fetch: FCM's token for this phone. */
    fun tokenChanged(fcmToken: String, store: LedgerStore) {
        if (!running) return
        token = fcmToken
        savedFor = null
        saveToken(store)
    }

    /** Sync turned off, or signing out: the token is removed from this phone's
     *  device document (when [inCloud] and still signed in) and deleted at
     *  FCM, so no push reaches this phone for the account. */
    suspend fun stop(store: LedgerStore, inCloud: Boolean) {
        val uid = Account.uid()
        // D-235 (M1): what may exist, not only what this process registered.
        val registeredFor = runCatching { mark(store, REGISTERED_KEY) }.getOrNull()
        val owed = runCatching { mark(store, DELETE_OWED_KEY) != null }.getOrDefault(false)
        val plan = PushCleanup.plan(
            inCloud = inCloud, uid = uid, inMemory = token != null || savedFor != null,
            registeredFor = registeredFor, wasRunning = running, deleteOwed = owed,
        )
        running = false
        token = null
        savedFor = null
        // D-235 (M2): each cloud call is bounded: offline, a Firestore write never completes, and Sign out waited on it.
        if (plan.clearCloud && uid != null) {
            val cleared = Bounded.run(CLOUD_TIMEOUT_MS) { device(uid, store)?.update(CloudProfile.tokenUpdate(null))?.awaitTask() }
            if (cleared) runCatching { store.rawKvSet(REGISTERED_KEY, ByteArray(0)) }
        }
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        messaging.isAutoInitEnabled = false
        if (plan.deleteAtFcm) deleteAtFcm(store, messaging)
    }

    /** D-235: deletes this phone's token at FCM (a deleted token reaches no phone, whatever a device document still
     *  says). Marked as owed first, so a delete that cannot finish now is tried again ([settleOwed]). */
    private suspend fun deleteAtFcm(store: LedgerStore, messaging: FirebaseMessaging) {
        if (running) return
        runCatching { store.rawKvSet(DELETE_OWED_KEY, "1".toByteArray(Charsets.UTF_8)) }
        if (Bounded.run(CLOUD_TIMEOUT_MS) { messaging.deleteToken().awaitTask() }) {
            runCatching {
                store.rawKvSet(DELETE_OWED_KEY, ByteArray(0))
                store.rawKvSet(REGISTERED_KEY, ByteArray(0))
            }
        }
    }

    /** D-235: SKIPTI came to the front not signed in with sync on (CloudSync decides): a delete still owed is tried
     *  again. Never in the emulator runs, and never while push runs. */
    fun settleOwed(store: LedgerStore) {
        if (Cloud.emulator || running) return
        scope.launch {
            if (runCatching { mark(store, DELETE_OWED_KEY) == null }.getOrDefault(true)) return@launch
            val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return@launch
            deleteAtFcm(store, messaging)
        }
    }

    /** A push mark's value, or null when it is not set (an empty value is cleared). */
    private fun mark(store: LedgerStore, key: String): String? =
        store.rawKvGet(key)?.decodeToString()?.takeIf { it.isNotEmpty() }

    private fun device(uid: String, store: LedgerStore): DocumentReference? {
        val id = runCatching { CloudProfile.deviceId(store) }.getOrNull() ?: return null
        return Cloud.db().collection("users").document(uid).collection("devices").document(id)
    }

    /** Writes the token into this phone's device document once per token and
     *  account. Until the document exists (CloudProfile.register) the write
     *  fails, and the next registration retries it. */
    fun saveToken(store: LedgerStore) {
        val t = token ?: return
        val uid = Account.uid() ?: return
        if (!running || savedFor == uid + t) return
        val doc = device(uid, store) ?: return
        scope.launch {
            runCatching { doc.update(CloudProfile.tokenUpdate(t)).awaitTask() }
                .onSuccess {
                    if (token == t) savedFor = uid + t
                    // D-235 (M1): kept in the vault, so a later process that never registers still removes it.
                    runCatching { store.rawKvSet(REGISTERED_KEY, uid.toByteArray(Charsets.UTF_8)) }
                }
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Asked once at a moment that explains itself (inviting, joining); the
     *  Settings row may ask again. Never at launch. */
    fun shouldAsk(context: Context): Boolean =
        !canPost(context) && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
    }

    /** "Group activity": where every SKIPTI push goes (FCM draws background
     *  pushes on it too, named in the manifest). */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, PushCopy.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** A push that arrived while SKIPTI is in front (FCM draws it only when
     *  SKIPTI is not), shown the same way. */
    fun show(
        context: Context, title: String, body: String, target: PushRoute.Target?, invite: Boolean = false,
        device: Boolean = false, group: String? = null,
    ) {
        if (!canPost(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (target != null) {
            intent.putExtra("kind", "record").putExtra("groupId", target.groupId).putExtra("expense", target.expense)
        } else if (invite) {
            intent.putExtra("kind", "invite")
        } else if (device) {
            intent.putExtra("kind", "device")
        } else if (group != null) {
            intent.putExtra("kind", "group").putExtra("groupId", group)
        }
        val open = PendingIntent.getActivity(
            context, (target?.expense ?: body).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_skipti)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // A later change to the same expense replaces its notification.
        manager.notify(target?.expense ?: group, 0, notification)
    }
}

/** D-196: asks for the notification permission once, at a moment that
 *  explains itself; a no-op once asked or allowed, and below Android 13. */
@Composable
fun rememberPushAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= 33 && Push.shouldAsk(context)) {
                Push.markAsked(context)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** D-196: FCM's entry point: a new token, and pushes that arrive while SKIPTI
 *  is in front (FCM draws the others itself, on the "Group activity"
 *  channel, and a tap opens MainActivity with the push's data). */
class SkiptiMessagingService : FirebaseMessagingService() {
    private fun store(): LedgerStore {
        VaultGate.appContextRef = applicationContext
        return LedgerStore(CloudSync.vaultPathOf(applicationContext))
    }

    override fun onNewToken(token: String) {
        Push.tokenChanged(token, store())
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        Push.show(
            applicationContext, n.title ?: "SKIPTI", n.body ?: return,
            PushRoute.target { message.data[it] }, invite = PushRoute.isInvite { message.data[it] },
            device = PushRoute.isDevice { message.data[it] }, group = PushRoute.groupId { message.data[it] },
        )
    }
}

/** D-196: Settings' notifications card, shown with an account only. */
@Composable
fun NotificationsCard(dark: Boolean, syncOn: Boolean) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(NotificationManager::class.java) }
    var allowed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(manager?.areNotificationsEnabled() == true && Push.canPost(context))
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = manager?.areNotificationsEnabled() == true && Push.canPost(context)
    }
    // Back from the phone's settings: read the switch again.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        allowed = manager?.areNotificationsEnabled() == true && Push.canPost(context)
        onPauseOrDispose {}
    }
    val on = allowed && syncOn
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.glassCard(dark)) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            SectionLabel(PushCopy.TITLE, dark)
            androidx.compose.material3.Text(
                Type.kicker(if (on) PushCopy.ON else PushCopy.OFF),
                style = Type.microLabel(dark).copy(color = if (on) Tok.owing(dark) else Tok.textDim(dark)),
            )
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
        androidx.compose.material3.Text(
            when {
                !syncOn -> PushCopy.EXPLAIN_SYNC_OFF
                allowed -> PushCopy.EXPLAIN_ON
                Build.VERSION.SDK_INT >= 33 && Push.shouldAsk(context) -> PushCopy.EXPLAIN_ASK
                else -> PushCopy.EXPLAIN_DENIED
            },
            style = Type.body(dark),
        )
        if (syncOn && !allowed) {
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
            if (Build.VERSION.SDK_INT >= 33 && Push.shouldAsk(context)) {
                SecondaryAction(PushCopy.TURN_ON, dark) {
                    Push.markAsked(context)
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                SecondaryAction(PushCopy.OPEN_SETTINGS, dark) {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

/** D-196: the expense a tapped push named. It may not be on this phone yet
 *  (the push can beat the records), so it waits for sync to bring it in. */
@Composable
fun PushedExpenseScreen(
    vaultPath: String,
    target: PushRoute.Target,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    var present by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(vaultPath, target, SyncStatus.revision) {
        if (present) return@LaunchedEffect
        present = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { LedgerStore(vaultPath).projectedExpenses().expenses.any { it.id == target.expense } }
                .getOrDefault(false)
        }
    }
    // D-217 final fix: opened only by a tapped push, a sheet with Close as on iOS (SkiptiApp.swift), waiting or not.
    if (present) {
        ExpenseDetailScreen(vaultPath, target.expense, onBack, onEdit, onDeleted, closeLabel = BarDecisions.CLOSE_LABEL)
    } else {
        // D-217: iOS's waiting page has no title (the expense's own title comes with the expense).
        SheetPage("", dark, onCancel = onBack, spacing = 12.dp, cancelLabel = BarDecisions.CLOSE_LABEL) {
            androidx.compose.material3.CircularProgressIndicator(color = Tok.accent(dark))
            androidx.compose.material3.Text(PushCopy.FETCHING, style = Type.bodyDim(dark))
        }
    }
}

/** D-235 (Android audit M1): what sign-out or sync off removes, decided from what may exist rather than from this
 *  process's memory. Pure: the JVM tests pin it. */
object PushCleanup {
    data class Plan(val clearCloud: Boolean, val deleteAtFcm: Boolean)

    /** [inCloud]: the account may still write its device document (Settings' sign-out, sync off), and [uid] is the
     *  account signed in. [inMemory]: this process holds a token. [registeredFor]: the account the vault says holds
     *  this phone's token in its device document. [wasRunning]: push ran in this process. [deleteOwed]: a delete at
     *  FCM did not finish before. */
    fun plan(
        inCloud: Boolean,
        uid: String?,
        inMemory: Boolean,
        registeredFor: String?,
        wasRunning: Boolean,
        deleteOwed: Boolean,
    ): Plan = Plan(
        clearCloud = inCloud && uid != null && (inMemory || registeredFor == uid),
        deleteAtFcm = wasRunning || inMemory || registeredFor != null || deleteOwed,
    )
}

/** D-235 (Android audit M2): a cloud call that may never finish (offline, a Firestore write resolves only on the
 *  server's acknowledgement, and Sign out waited on one for good) gets [ms]. True when it finished, false when it
 *  failed or ran out of time. The caller's own cancellation is never swallowed. */
object Bounded {
    suspend fun run(ms: Long, block: suspend () -> Unit): Boolean = try {
        kotlinx.coroutines.withTimeout(ms) { block() }
        true
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
