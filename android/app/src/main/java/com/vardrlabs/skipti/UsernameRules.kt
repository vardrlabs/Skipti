package com.vardrlabs.skipti

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** C2: the username rule as the cloud decides it (firebase/functions/people.js),
 *  so the phone answers at once. The list is the cloud's own file, bundled
 *  as an asset (build.gradle.kts). iOS's twin is UsernameRules.swift. */
object UsernameRules {
    enum class Problem { SHAPE, UNAVAILABLE }

    /** The list, with every term's pattern compiled once when it is made
     *  (as people.js does): `problem` runs on each keystroke and never
     *  compiles. Equality is the terms'. */
    data class TermList(val reserved: List<String>, val anywhere: List<String>, val whole: List<String>, val raw: List<String>) {
        val reservedPatterns: List<Regex> = reserved.map { Regex("^${UsernameRules.pattern(it)}$") }
        val anywherePatterns: List<Regex> = anywhere.map { Regex(UsernameRules.pattern(it)) }
        val wholePatterns: List<Regex> = whole.map { Regex("^${UsernameRules.pattern(it)}$") }
    }

    @Volatile var list: TermList? = null
        private set

    fun load(context: Context) {
        if (list == null) list = runCatching {
            parse(context.assets.open("usernames.json").bufferedReader().use { it.readText() })
        }.getOrNull()
    }

    fun parse(text: String): TermList {
        val o = Json.parseToJsonElement(text).jsonObject
        fun words(key: String) = (o[key] as JsonArray).map { (it as JsonPrimitive).content }
        return TermList(words("reserved"), words("anywhere"), words("whole"), words("raw"))
    }

    private val shape = Regex("^[a-z0-9_]{3,20}$")

    /** Trimmed (newlines too, as the cloud does), without a leading @, lower case. */
    fun handle(raw: String): String = raw.trim().removePrefix("@").lowercase()

    /** The change screen's suggestions: never the handle you already have
     *  (the cloud counts your own as free). */
    fun changeSuggestions(all: List<String>, current: String): List<String> = all.filter { it != current }

    /** Why a username cannot be had; null when the cloud alone can say (taken). */
    fun problem(raw: String, list: TermList? = this.list): Problem? {
        val h = handle(raw)
        if (!shape.matches(h)) return Problem.SHAPE
        if (list != null && blocked(h, list)) return Problem.UNAVAILABLE
        return null
    }

    /** Each run of n equal letters may repeat, never shrink: boobs is b+o{2,}b+s+. */
    fun pattern(term: String): String {
        val chars = term.replace("_", "")
        val out = StringBuilder()
        var i = 0
        while (i < chars.length) {
            var j = i
            while (j < chars.length && chars[j] == chars[i]) j++
            out.append(Regex.escape(chars[i].toString()).let { if (it.startsWith("\\Q")) chars[i].toString() else it })
            out.append(if (j - i > 1) "{${j - i},}" else "+")
            i = j
        }
        return out.toString()
    }

    private val leetMap = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b')
    private fun leet(s: String) = s.map { leetMap[it] ?: it }.joinToString("")

    fun blocked(h: String, list: TermList): Boolean {
        if (list.raw.any { h.contains(it) }) return true
        val full = leet(h).replace("_", "")
        val bare = h.replace(Regex("[0-9_]"), "")
        val parts = h.split("_").filter { it.isNotEmpty() }.map(::leet) + h.split(Regex("[0-9_]+")).filter { it.isNotEmpty() }
        if (list.reservedPatterns.any { r -> listOf(full, bare).any { r.matches(it) } }) return true
        if (list.anywherePatterns.any { r -> listOf(full, bare).any { r.containsMatchIn(it) } }) return true
        return list.wholePatterns.any { r -> (listOf(full, bare) + parts).any { r.matches(it) } }
    }
}

/** C2: the account's username, read from its cloud profile. */
sealed class UsernameState {
    data object None : UsernameState()
    data class CanChange(val handle: String) : UsernameState()
    data class Final(val handle: String) : UsernameState()

    companion object {
        /** null: no profile document (not signed in, offline, or not registered yet). */
        fun from(data: Map<String, Any?>?): UsernameState? {
            if (data == null) return null
            val h = (data["username"] as? String)?.takeIf { it.isNotEmpty() } ?: return None
            return if (data["usernameChanged"] == true) Final(h) else CanChange(h)
        }

        /** What a screen keeps after a read. Signed out: nothing. A read that
         *  failed (null) never forgets what was known: Home reads on every sync
         *  revision, and dropping to null would take the card away. Twin of
         *  iOS's `UsernameState.merge(known:read:signedIn:)`. */
        fun merge(known: UsernameState?, read: UsernameState?, signedIn: Boolean): UsernameState? {
            if (!signedIn) return null
            return read ?: known
        }

        /** The step after sign-in is only for an account known to have no
         *  username; unread (offline, slow) goes on, and the card asks later. */
        fun needsStep(state: UsernameState?): Boolean = state == None

        /** D-254 look fix 1: the "Pick your username" card (Balance and You) only for an account known to have no
         *  username AND approved: until then the check and the save are refused for approval (the twin of iOS's
         *  `UsernameState.cardShows(_:approval:)`). */
        fun cardShows(state: UsernameState?, approval: ApprovalState): Boolean =
            state == None && Approval.allowsCloud(approval)
    }
}
