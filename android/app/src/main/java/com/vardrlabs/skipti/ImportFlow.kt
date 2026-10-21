package com.vardrlabs.skipti

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BudgetBakersOptions
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.DateFormat
import uniffi.skipti_core.ImportPlan
import uniffi.skipti_core.ImportProfile
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.MemberBinding
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.PortException
import uniffi.skipti_core.PortExpense
import uniffi.skipti_core.PortGroup
import uniffi.skipti_core.SplitwiseOptions
import uniffi.skipti_core.ffiCreateNamedGroup
import uniffi.skipti_core.ffiPlanImport

/**
 * D-166 (SPEC §9.5): import from another app, as a person would use it; the
 * twin of ios/Skipti/App/ImportFlow.swift.
 *
 * Pick the app the file came from, pick the file, name the new group and its
 * currency, say which person in a Splitwise sheet is you, review, apply.
 * §9.5's "nothing exists until Apply" holds: the new group is built with
 * `ffiCreateNamedGroup` but not saved, the plan runs against the ledger plus
 * that group, and only after `ffiApplyImportPlan` accepts the merge are the
 * group and the expenses written, the expenses as sync records in one vault
 * session ([LedgerStore.appendExpenses]). The older `--import` screen is a
 * verification surface and stays one.
 */
enum class ImportSource(val title: String, val detail: String, val fallbackGroupName: String) {
    SPLITWISE(
        "Splitwise",
        "One group's spreadsheet export, a .csv file. Everyone in it becomes a person in a new group here.",
        "Splitwise import",
    ),
    BUDGET_BAKERS(
        "Wallet by BudgetBakers",
        // D-249: Just me or a new group, chosen below.
        "A records export, a .csv file. Your spending comes in as your own expenses.",
        "Wallet",
    ),
}

object ImportCopy {
    const val TITLE = "Import"
    const val ENTRY = "Import from another app"
    const val ENTRY_DETAIL = "Splitwise and Wallet by BudgetBakers exports. You see everything before it is added."
    const val INTRO = "Bring expenses in from another app. You see everything before anything is added."
    const val FROM = "From"
    const val FILE = "File"
    const val CHOOSE_FILE = "Choose file"
    const val CHOOSE_ANOTHER = "Choose another file"
    const val NEW_GROUP = "New group"
    const val GROUP_NAME = "Group name"
    const val SETTLES_IN = "Settles in"
    const val SETTLES_DETAIL =
        "A group settles in one currency, and it cannot be changed later. Expenses keep their own currency."
    const val PEOPLE = "People in this file"
    const val WHICH_IS_YOU = "Which one is you? The others become people in the group."
    const val YOU = "You"
    const val YOUR_NAME = "Your name"
    const val REVIEW = "Review import"
    const val TOO_LARGE = "That file is too large to import. The limit is 5 MB."
    const val NOT_TEXT = "That file is not a text file. Choose the .csv file the app exported."
    const val UNREADABLE = "That file could not be opened."
    const val SHOW_GROUP = "Show the group"
    fun done(count: Int, group: String) =
        if (count == 1) "1 expense added to $group." else "$count expenses added to $group."
    fun peopleCount(n: Int) = if (n == 1) "1 person" else "$n people"

    /** §11.6's register: what went wrong, in the person's terms. */
    fun describe(error: Throwable): String = when (error) {
        is ImportSetup.ReadException -> if (error.tooLarge) TOO_LARGE else NOT_TEXT
        is PortException.CsvSyntax -> "That file is not a valid CSV file."
        is PortException.SheetStructure -> "That file does not look like the export this app expects: ${error.v1}."
        is PortException.UnmappedMembers -> "These people in the file have no match: ${error.v1.joinToString(", ")}."
        is PortException.InvalidLedger -> "The import was refused: ${error.v1}."
        is NeedsYourNameException -> "Add your name first."
        is CoreException -> "The group could not be made: ${error.message}."
        else -> "The import did not work: ${error.message ?: error::class.simpleName}."
    }
}

/** The pure steps, tested without a screen. */
object ImportSetup {
    const val MAX_BYTES = 5 * 1024 * 1024

    class ReadException(val tooLarge: Boolean) : Exception(if (tooLarge) "too large" else "not text")

    /** The file's text: at most 5 MB, UTF-8, one leading byte-order mark dropped. */
    fun text(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw ReadException(tooLarge = true)
        val decoder = Charsets.UTF_8.newDecoder()
        val text = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ReadException(tooLarge = false)
        }
        return text.removePrefix("﻿")
    }

    /** Reads at most one byte past the limit, so a huge file is refused
     *  without being read whole (`readNBytes` needs Android 13). */
    fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= MAX_BYTES) {
            val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    val emptyLedger = LedgerSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), null)

    /** A stand-in participant id (32 hex) for planning before any real id exists. */
    fun placeholderId(index: Int): String = String.format(Locale.ROOT, "%032x", index + 1)

    /** The person columns of a Splitwise group sheet. Core refuses a plan with
     *  no bindings and names every column it could not map. */
    fun people(csv: String): List<String> = try {
        ffiPlanImport(
            csv,
            ImportProfile.SplitwiseGroup(SplitwiseOptions(DateFormat.ISO_YMD, emptyList(), null)),
            emptyLedger,
        )
        emptyList()
    } catch (e: PortException.UnmappedMembers) {
        e.v1
    }

    /** The column that is you: the one whose name is yours, ignoring case. */
    fun guessYou(people: List<String>, yourName: String?): String? {
        val yours = yourName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return people.firstOrNull { it.trim().lowercase() == yours }
    }

    /** "tahoe_trip_2026-09-21_export.csv" → "Tahoe trip": the words of the file
     *  name without its dates, numbers and "export"; nothing left → the
     *  source's name. */
    fun groupName(fileName: String, source: ImportSource): String {
        val stem = fileName.substringBeforeLast('.', fileName).let { if (fileName.startsWith(".") && it.isEmpty()) fileName else it }
        val words = stem.replace('_', ' ').replace('-', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && !it.all(Char::isDigit) && it.lowercase() != "export" }
            .joinToString(" ")
        if (words.isEmpty()) return source.fallbackGroupName
        return words.replaceFirstChar { it.uppercase() }
    }

    /** The code most rows use; a tie goes to the one seen first. */
    fun mostCommon(codes: List<String>): String? {
        val counts = codes.groupingBy { it }.eachCount()
        val max = counts.values.maxOrNull() ?: return null
        return codes.first { counts[it] == max }
    }

    fun profile(source: ImportSource, bindings: List<MemberBinding>, groupId: String?, selfId: String): ImportProfile =
        when (source) {
            ImportSource.SPLITWISE ->
                ImportProfile.SplitwiseGroup(SplitwiseOptions(DateFormat.ISO_YMD, bindings, groupId))
            ImportSource.BUDGET_BAKERS ->
                ImportProfile.BudgetBakersWallet(BudgetBakersOptions(selfId, groupId))
        }

    /** The settlement currency to offer: what most of the file's rows use,
     *  read from a plan with stand-in ids; else the default currency. */
    fun likelyCurrency(csv: String, source: ImportSource, people: List<String>, fallback: String): String {
        val bindings = people.mapIndexed { i, name -> MemberBinding(name, placeholderId(i)) }
        val plan = try {
            ffiPlanImport(csv, profile(source, bindings, null, placeholderId(0)), emptyLedger)
        } catch (_: Exception) {
            return fallback
        }
        return mostCommon(plan.expenses.map { it.currencyCode }) ?: fallback
    }

    /** Each sheet column to its member in the new group: you to your id, the
     *  others by name (core made the group from those same names). */
    fun bindings(people: List<String>, you: String, group: NamedGroupWire): List<MemberBinding> =
        people.mapNotNull { column ->
            if (column == you) return@mapNotNull MemberBinding(column, group.selfId)
            val key = column.trim().lowercase()
            group.members.firstOrNull { it.id != group.selfId && it.name.lowercase() == key }
                ?.let { MemberBinding(column, it.id) }
        }

    /** The ledger the plan is checked against: what is saved, plus the group
     *  that is not saved yet. */
    fun adding(group: NamedGroupWire, ledger: LedgerSnapshot): LedgerSnapshot = ledger.copy(
        groups = ledger.groups + PortGroup(
            group.group.id, group.group.name, group.group.settlementCurrency, group.group.members,
        ),
    )

    fun dto(e: PortExpense, addedBy: String): ExpenseDTO = ExpenseDTO(
        payer = e.payer,
        paidMinor = e.paidMinor,
        currencyCode = e.currencyCode,
        group = e.group,
        conversion = e.conversion?.let { c ->
            ConversionDTO(
                convertedMinor = c.convertedMinor,
                convertedCurrency = c.convertedCurrency,
                rateNum = c.rateNum,
                rateDen = c.rateDen,
                rateTimestampMs = c.rateTimestampMs.toLong(),
            )
        },
        shares = e.shares.map { ExpenseDTO.ShareDTO(id = it.id, amountMinor = it.amountMinor) },
        note = e.note,
        timestampMs = e.timestampMs?.let { localWallTime(it.toLong()) },
        category = e.category,
        addedBy = addedBy,
    )

    /** The importers read a file's dates as UTC wall times. Saved, the same
     *  wall time is placed in this phone's time zone; a date-only row (UTC
     *  midnight) lands at local noon, which stays on the same UTC day from
     *  UTC−12 to UTC+12, so duplicate checks still match. iOS: localWallTime. */
    fun localWallTime(ms: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val day = 86_400_000L
        val wall = if (ms % day == 0L) ms + day / 2 else ms
        return maxOf(0L, wall - zone.getOffset(wall))
    }
}

/** D-249 (U10): where a Wallet by BudgetBakers file goes: Just me (the personal scope, first) or a new group with just
 *  you. A Splitwise file is always a new group. iOS's twin is `ImportTarget`. */
enum class ImportTarget(val title: String) {
    JUST_ME(SpendingCopy.IMPORT_INTO_JUST_ME),
    NEW_GROUP(SpendingCopy.IMPORT_AS_GROUP);

    companion object {
        /** The target a source can take: Splitwise only a group. */
        fun effective(chosen: ImportTarget, source: ImportSource): ImportTarget =
            if (source == ImportSource.BUDGET_BAKERS) chosen else NEW_GROUP
    }
}

/** One import from choosing to writing, with the screen kept out of it. */
class ImportJob(
    /** D-249: null for an import into Just me (no group is made). */
    val group: NamedGroupWire?,
    val plan: ImportPlan,
    /** The saved ledger plus the new group: what the plan was checked against. */
    val existing: LedgerSnapshot,
    /** Your name, when this import is what gives you one. */
    val newName: String?,
    val selfId: String,
    /** Your name for the review's wording when there is no group (Just me). */
    val selfName: String = ExpenseCopy.YOU,
) {
    /** Participant id → name in the new group, for the review's wording. */
    val names: Map<String, String> get() = group?.members?.associate { it.id to it.name } ?: mapOf(selfId to selfName)

    /** Where the review says it went: the group's name, or Just me. */
    val ledgerName: String get() = group?.group?.name ?: SpendingCopy.JUST_ME

    /** After `ffiApplyImportPlan` accepted the merge: your name if this gave
     *  you one, the group, then the expenses in one vault session. */
    fun commit(expenses: List<PortExpense>, store: LedgerStore) {
        newName?.let { store.setYourName(it) }
        group?.let { store.adopt(it) }
        store.appendExpenses(expenses.map { ImportSetup.dto(it, selfId) })
    }

    companion object {
        /** D-249: a Wallet file into Just me: no group, no name needed; every expense is yours, in the personal scope
         *  (core's importer with `group: None`). */
        fun prepareJustMe(store: LedgerStore, csv: String): ImportJob {
            val me = store.ensureSelf()
            val existing = store.snapshot()
            val plan = ffiPlanImport(csv, ImportSetup.profile(ImportSource.BUDGET_BAKERS, emptyList(), null, me.id), existing)
            return ImportJob(null, plan, existing, null, me.id, me.name ?: ExpenseCopy.YOU)
        }

        /** Builds the group (not saved) and plans the file against it. */
        fun prepare(
            store: LedgerStore,
            csv: String,
            source: ImportSource,
            groupName: String,
            currency: String,
            people: List<String>,
            you: String?,
            typedName: String,
        ): ImportJob {
            val me = store.ensureSelf()
            val chosen = if (source == ImportSource.SPLITWISE) you else null
            val typed = typedName.trim()
            val selfName = me.name ?: chosen ?: typed.ifEmpty { null } ?: throw NeedsYourNameException()
            val others = if (source == ImportSource.SPLITWISE) people.filter { it != chosen } else emptyList()
            val group = ffiCreateNamedGroup(groupName, currency, MemberWire(me.id, selfName), others)
            val bindings = chosen?.let { ImportSetup.bindings(people, it, group) } ?: emptyList()
            val existing = ImportSetup.adding(group, store.snapshot())
            val plan = ffiPlanImport(
                csv, ImportSetup.profile(source, bindings, group.group.id, me.id), existing,
            )
            return ImportJob(group, plan, existing, if (me.name == null) selfName else null, me.id)
        }
    }
}

/** Settings → Your data → Import, and Balance's empty state. */
@Composable
fun ImportFlowScreen(vaultPath: String, onBack: () -> Unit, onDone: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(ImportSource.SPLITWISE) }
    var csv by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var people by remember { mutableStateOf(listOf<String>()) }
    var you by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(DefaultCurrency.current) }
    var typedName by remember { mutableStateOf("") }
    var needsName by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<ImportJob?>(null) }
    var busy by remember { mutableStateOf(false) }
    // D-249: a Wallet file's target, Just me first.
    var chosenTarget by remember { mutableStateOf(ImportTarget.JUST_ME) }
    val target = ImportTarget.effective(chosenTarget, source)

    LaunchedEffect(vaultPath) {
        needsName = withContext(Dispatchers.IO) { runCatching { LedgerStore(vaultPath).ensureSelf().name == null }.getOrDefault(false) }
    }

    fun reset() {
        csv = null; fileName = ""; people = emptyList(); you = null; error = null
    }

    fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file.csv")

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        val chosenSource = source
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { ImportSetup.readCapped(it) }
                    ?: throw java.io.IOException("no stream")
                val text = ImportSetup.text(bytes)
                val found = if (chosenSource == ImportSource.SPLITWISE) ImportSetup.people(text) else emptyList()
                val me = runCatching { LedgerStore(vaultPath).ensureSelf() }.getOrNull()
                val name = displayName(uri)
                listOf(
                    text, name, found, ImportSetup.guessYou(found, me?.name),
                    ImportSetup.groupName(name, chosenSource),
                    ImportSetup.likelyCurrency(text, chosenSource, found, DefaultCurrency.current),
                )
            }
            withContext(Dispatchers.Main) {
                busy = false
                outcome.fold(
                    onSuccess = { r ->
                        csv = r[0] as String
                        fileName = r[1] as String
                        @Suppress("UNCHECKED_CAST")
                        people = r[2] as List<String>
                        you = r[3] as String?
                        groupName = r[4] as String
                        currency = r[5] as String
                    },
                    onFailure = { e ->
                        reset()
                        error = if (e is java.io.IOException) ImportCopy.UNREADABLE else ImportCopy.describe(e)
                    },
                )
            }
        }
    }

    val current = job
    if (current != null) {
        // D-217 (oddity 5): system Back leaves the review for the setup page, as its own back button does.
        BackHandler { job = null }
        ImportReviewScreen(current, vaultPath, dark, onBack = { job = null }, onDone = onDone)
        return
    }

    val ready = if (target == ImportTarget.JUST_ME) csv != null && !busy else csv != null && groupName.isNotBlank() && !busy &&
        (source == ImportSource.BUDGET_BAKERS || you != null) &&
        (!needsName || source == ImportSource.SPLITWISE || typedName.isNotBlank())

    PushedPage(ImportCopy.TITLE, dark, onBack, spacing = 12.dp, top = BarDecisions.formTop(headerless = true).dp) {
        Text(ImportCopy.INTRO, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
        SectionLabel(ImportCopy.FROM, dark)
        ImportSource.entries.forEach { option ->
            ChoiceRow(
                selected = option == source,
                dark = dark,
                onClick = { if (source != option) { source = option; reset() } },
            ) {
                Text(option.title, style = Type.body(dark))
                Text(option.detail, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            }
        }
        // D-249 (U10): a Wallet file goes into Just me or a new group.
        if (source == ImportSource.BUDGET_BAKERS) {
            ImportTarget.entries.forEach { option ->
                ChoiceRow(selected = option == chosenTarget, dark = dark, onClick = { chosenTarget = option }) {
                    Text(option.title, style = Type.body(dark))
                }
            }
        }
        SectionLabel(ImportCopy.FILE, dark)
        if (csv != null) {
            Text(fileName, style = Type.body(dark))
            if (source == ImportSource.SPLITWISE) Text(ImportCopy.peopleCount(people.size), style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
        }
        SecondaryAction(if (csv == null) ImportCopy.CHOOSE_FILE else ImportCopy.CHOOSE_ANOTHER, dark) {
            openDocument.launch(arrayOf("*/*"))
        }
        if (csv != null && target == ImportTarget.NEW_GROUP) {
            SectionLabel(ImportCopy.NEW_GROUP, dark)
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text(ImportCopy.GROUP_NAME) },
                singleLine = true,
                textStyle = Type.body(dark),
                modifier = Modifier.fillMaxWidth(),
            )
            SectionLabel(ImportCopy.SETTLES_IN, dark)
            CurrencyChips(currency, { currency = it }, dark)
            Text(ImportCopy.SETTLES_DETAIL, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            if (source == ImportSource.SPLITWISE) {
                SectionLabel(ImportCopy.PEOPLE, dark)
                people.forEach { person ->
                    ChoiceRow(selected = person == you, dark = dark, onClick = { you = person }, marker = "You") {
                        Text(person, style = Type.body(dark))
                    }
                }
                Text(ImportCopy.WHICH_IS_YOU, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            } else if (needsName) {
                SectionLabel(ImportCopy.YOU, dark)
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    label = { Text(ImportCopy.YOUR_NAME) },
                    singleLine = true,
                    textStyle = Type.body(dark),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        error?.let { ErrorLine(it, dark, detail = true) }
        PrimaryAction(ImportCopy.REVIEW, dark, enabled = ready) {
            val text = csv ?: return@PrimaryAction
            busy = true
            error = null
            scope.launch(Dispatchers.IO) {
                val outcome = runCatching {
                    if (target == ImportTarget.JUST_ME) ImportJob.prepareJustMe(LedgerStore(vaultPath), text)
                    else ImportJob.prepare(LedgerStore(vaultPath), text, source, groupName, currency, people, you, typedName)
                }
                withContext(Dispatchers.Main) {
                    busy = false
                    outcome.fold(onSuccess = { job = it }, onFailure = { error = ImportCopy.describe(it) })
                }
            }
        }
    }
}

/** A choice in a list: the whole row is the target; the chosen one says so. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    marker: String = "✓",
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics { this.selected = selected },
    ) {
        Column(Modifier.weight(1f)) { content() }
        if (selected) {
            // iOS's "You" tag is 14, its checkmark a symbol at the row's size.
            val tag = Type.body(dark).copy(color = Tok.groundAccent(dark))
            Text(marker, style = if (marker == "You") tag.copy(fontSize = TypeScale.detail.sp) else tag)
        }
    }
}

/** §9.5's review, profile-blind, with the whole row as the include switch. */
@Composable
private fun ImportReviewScreen(
    job: ImportJob,
    vaultPath: String,
    dark: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val model = remember(job) { ImportReview(job.plan, AppLocale.current, job.names) }
    // The model keeps plain fields; this counter makes a change redraw.
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // Reading the counter here is what subscribes this screen to it.
    if (version < 0) return

    PushedPage("Review", dark, onBack, spacing = 12.dp) {
        model.totalsText.forEach { Text("Total $it", style = Type.numericColored(13, Tok.textDim(dark))) }
        Text(Type.kicker("Nothing is created yet"), style = Type.microLabel(dark))
        SectionLabel("What will be created", dark)
        val reviewing = model.appliedState is ImportReview.AppliedState.Reviewing
        model.proposals.forEach { p ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = reviewing && !busy, role = Role.Checkbox) {
                        model.setIncluded(!p.included, forId = p.id)
                        version++
                    }
                    .padding(vertical = 6.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Proposal ${p.id + 1}, ${p.noteText ?: "untitled"}, ${Placeholder.spoken(p.amountText)} ${p.currencyCode}" +
                            (p.dateText?.let { ", $it" } ?: "") +
                            (p.duplicateOf?.let { ". Duplicate: ${it.label}" } ?: "")
                        stateDescription = if (p.included) "Included" else "Left out"
                    },
            ) {
                // Shows the state; the whole row is what toggles it.
                androidx.compose.material3.Checkbox(
                    checked = p.included,
                    onCheckedChange = null,
                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                        checkedColor = Tok.accent(dark),
                        uncheckedColor = Tok.groundFaint(dark),
                        checkmarkColor = Tok.bg(dark),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        p.noteText ?: "Untitled",
                        style = Type.body(dark).copy(color = if (p.included) Tok.text(dark) else Tok.groundFaint(dark)),
                    )
                    p.duplicateOf?.let {
                        Text(Type.kicker("Duplicate: ${it.label}"), style = Type.microLabel(dark).copy(color = Tok.owed(dark)))
                    }
                    Row {
                        p.dateText?.let {
                            Text(it, style = Type.numericColored(12, Tok.groundFaint(dark)))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            "${p.amountText} ${p.currencyCode}",
                            style = Type.numericColored(15, if (p.included) Tok.text(dark) else Tok.groundFaint(dark)),
                        )
                    }
                }
            }
        }
        if (model.rejections.isNotEmpty()) {
            SectionLabel("Refused rows", dark)
            model.rejections.forEach { r ->
                Column {
                    Text("Line ${r.lineNumber}", style = Type.numericColored(12, Tok.groundFaint(dark)))
                    Text(r.reason, style = Type.bodyDim(dark))
                }
            }
        }
        when (val state = model.appliedState) {
            is ImportReview.AppliedState.Reviewing -> {
                Text("Applying writes all selected expenses at once.", style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
                val label = when (model.includedCount) {
                    0 -> "Select at least one expense"
                    1 -> "Import 1 expense"
                    else -> "Import ${model.includedCount} expenses"
                }
                PrimaryAction(label, dark, enabled = model.hasIncludedExpenses && !busy) {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val applied = model.apply(job.existing) { job.commit(it, LedgerStore(vaultPath)) }
                        // D-211 fix 5: the imported group and expenses go up at once (iOS's finish does).
                        if (applied is ImportReview.AppliedState.Done) CloudSync.ledgerChanged(vaultPath)
                        withContext(Dispatchers.Main) {
                            busy = false
                            version++
                            // D-224 (Design 7): the page shows what was imported; the success haptic is all it adds.
                            if (applied is ImportReview.AppliedState.Done) Haptics.play(view, HapticKind.SUCCESS)
                        }
                    }
                }
            }
            is ImportReview.AppliedState.Done -> {
                Text(
                    ImportCopy.done(state.expenseCount, job.ledgerName),
                    style = Type.body(dark).copy(color = Tok.owing(dark)),
                )
                // D-249: "Show the group"; "Done" for Just me.
                PrimaryAction(if (job.group == null) ProfileCopy.DONE else ImportCopy.SHOW_GROUP, dark, onClick = onDone)
            }
            is ImportReview.AppliedState.Failed -> ErrorLine(state.message, dark)
        }
    }
}
