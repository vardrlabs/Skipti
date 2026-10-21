package com.vardrlabs.skipti

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * D-210 (Task 8 review): the work that removes someone from a group, records
 * Settle first's payments, decides a request or deletes a group runs here, in
 * the app's lifetime, never in a screen's scope. A screen made again (a
 * rotation, the process alive) finds the same job and how far it got, and a
 * tap while it runs is refused, so a payment is never appended twice and a
 * delete always ends with this phone's copy marked read-only.
 *
 * A job is keyed by what it changes: (group, member) for a removal, (group)
 * for a delete. Its status is Compose state, written on the main thread
 * through [MainThread] (the seam [GroupRoles] uses). On process death the job
 * is gone; the screens' saved state is what was recorded, and Settle first
 * retries only what is not in yet.
 */
object GroupJobs {
    data class Key(val group: String, val member: String?) {
        companion object {
            fun removal(group: String, member: String) = Key(group, member)
            fun delete(group: String) = Key(group, null)
        }
    }

    sealed interface Status {
        data object Running : Status
        data object Done : Status
        data class Failed(val message: String) : Status
    }

    /** Thrown by work that has its own words for the screen. */
    class Failure(message: String) : Exception(message)

    /** The app's lifetime: one job failing never stops another. A test seam. */
    @Volatile var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** D-191's rule: Compose state made on the main thread before any frame. */
    fun createState() = Unit

    /** Screens read it; only this object sets it (on the main thread). */
    var statuses by mutableStateOf<Map<Key, Status>>(emptyMap())
        private set

    private class Settle(val payments: List<SettlementDTO>, val progress: SettleFirstProgress)

    private val lock = Any()
    private val truth = HashMap<Key, Status>()
    private val settling = HashMap<Key, Settle>()

    fun status(key: Key): Status? = statuses[key]

    /** The screens' state follows the truth, in the order it changed. */
    private fun publish() {
        MainThread.run { statuses = synchronized(lock) { HashMap(truth) } }
    }

    /**
     * Runs [work] for [key] unless it is running (then nothing runs and the
     * answer is null). A terminal status left from before, seen or not, is
     * replaced. A failure becomes [Status.Failed] with [describe]'s words, and
     * may be started again.
     */
    fun start(key: Key, describe: (Throwable) -> String, work: suspend () -> Unit): Job? {
        synchronized(lock) {
            val now = truth[key]
            // Only a running job refuses. A Done or Failed nobody saw is
            // replaced: a person removed, added again and removed again is a
            // new flow, and must not be finished by the last one's status.
            if (now == Status.Running) return null
            truth[key] = Status.Running
        }
        publish()
        return scope.launch {
            var outcome: Status = Status.Failed(InviteCopy.OFFLINE)
            try {
                work()
                outcome = Status.Done
            } catch (e: Exception) {
                outcome = Status.Failed(describe(e))
                if (e is CancellationException) throw e
            } finally {
                synchronized(lock) {
                    truth[key] = outcome
                    // Finished: its Settle first progress is spent, and the
                    // next flow for the same person starts from nothing.
                    if (outcome == Status.Done) settling.remove(key)
                }
                publish()
            }
        }
    }

    /** A screen has acted on what the job said (or left the flow): forgets it
     *  and its progress. A running job is never forgotten. */
    fun clear(key: Key) {
        synchronized(lock) {
            if (truth[key] == Status.Running) return
            truth.remove(key)
            settling.remove(key)
        }
        publish()
    }

    /** Settle first's progress for these [payments]: the job's when it has one
     *  for the same payments, else [saved] (the screen's copy, restored after
     *  a process death) or a fresh one, kept from now on. */
    fun settleProgress(key: Key, payments: List<SettlementDTO>, saved: SettleFirstProgress?): SettleFirstProgress =
        synchronized(lock) {
            settling[key]?.takeIf { it.payments == payments }?.progress
                ?: (saved?.takeIf { it.total == payments.size } ?: SettleFirstProgress(payments.size)).also {
                    settling[key] = Settle(payments, it)
                }
        }

    /** The job's progress for these [payments], if it has one. */
    fun progress(key: Key, payments: List<SettlementDTO>): SettleFirstProgress? =
        synchronized(lock) { settling[key]?.takeIf { it.payments == payments }?.progress }
}
