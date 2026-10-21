package com.vardrlabs.skipti

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.PortException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §9.5's import: pick a sheet → plan it through core (the dry run) →
 * review exactly what [ImportReview] renders → apply as ONE unit → and
 * only then does the merged snapshot reach the vault, through the same
 * envelope encoders everything else reads. The profile is chosen BEFORE
 * planning (it describes the FILE); the review is profile-blind, exactly
 * like iOS's ImportConfirmScreen, which iOS mounts nowhere; this screen
 * is the `--import` verification surface, and the polished bindings UI
 * is product residue on both shells.
 */
@Composable
fun ImportScreen(store: LedgerStore) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedText by remember { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    var isSplitwise by rememberSaveable { mutableStateOf(true) }
    var groupId by rememberSaveable { mutableStateOf("") }
    var bindingsText by rememberSaveable { mutableStateOf("") }
    var selfId by rememberSaveable { mutableStateOf("") }
    var review by remember { mutableStateOf<ImportReview?>(null) }
    var existing by remember { mutableStateOf<LedgerSnapshot?>(null) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: ByteArray(0)
                // One leading U+FEFF is a read-back artefact (§5.15's
                // reverse direction), never content:
                val text = bytes.decodeToString().removePrefix("\uFEFF")
                withContext(Dispatchers.Main) {
                    pickedText = text
                    pickedName = uri.lastPathSegment?.substringAfterLast('/')
                    review = null
                    errorText = null
                    busy = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorText = "The file could not be read: $e"
                    busy = false
                }
            }
        }
    }

    fun planSheet() {
        val text = pickedText ?: return
        busy = true
        errorText = null
        scope.launch(Dispatchers.IO) {
            try {
                val snap = store.snapshot()
                val profile = if (isSplitwise) {
                    val members = bindingsText.lines()
                        .mapNotNull { line ->
                            val idx = line.indexOf('=')
                            if (idx <= 0) null
                            else uniffi.skipti_core.MemberBinding(
                                line.substring(0, idx).trim(),
                                line.substring(idx + 1).trim(),
                            )
                        }
                    val options = uniffi.skipti_core.SplitwiseOptions(
                        dateFormat = uniffi.skipti_core.DateFormat.ISO_YMD,
                        members = members,
                        group = groupId.trim().ifEmpty { null },
                    )
                    uniffi.skipti_core.ImportProfile.SplitwiseGroup(options)
                } else {
                    val options = uniffi.skipti_core.BudgetBakersOptions(
                        selfParticipant = selfId.trim(),
                        group = null,
                    )
                    uniffi.skipti_core.ImportProfile.BudgetBakersWallet(options)
                }
                val plan = uniffi.skipti_core.ffiPlanImport(text, profile, snap)
                withContext(Dispatchers.Main) {
                    existing = snap
                    review = ImportReview(plan, AppLocale.current)
                    busy = false
                }
            } catch (e: PortException.CsvSyntax) {
                withContext(Dispatchers.Main) {
                    errorText = "That file is not a valid CSV sheet."
                    busy = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorText = "The file was refused: ${e.message ?: e::class.simpleName}"
                    busy = false
                }
            }
        }
    }

    fun applyImport() {
        val model = review ?: return
        val snap = existing ?: return
        busy = true
        scope.launch(Dispatchers.IO) {
            val state = model.apply(snap)
            // Persist EXACTLY ON SUCCESS (the atomicity line): a failed
            // apply writes nothing, asserted rather than assumed.
            if (state is ImportReview.AppliedState.Done && model.merged != null) {
                store.writeSnapshot(model.merged!!)
            }
            withContext(Dispatchers.Main) { busy = false }
        }
    }

    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Import", style = Type.display(dark))
            Spacer(Modifier.height(12.dp))

            val model = review
            if (model == null) {
                SectionLabel("Source", dark)
                Spacer(Modifier.height(6.dp))
                Row {
                    FilterChip(
                        selected = isSplitwise,
                        onClick = { isSplitwise = true },
                        label = { Text("Splitwise group sheet") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = !isSplitwise,
                        onClick = { isSplitwise = false },
                        label = { Text("BudgetBakers statement") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (isSplitwise) {
                    OutlinedTextField(
                        value = groupId,
                        onValueChange = { groupId = it },
                        label = { Text("Group id (empty = personal)") },
                        singleLine = true,
                        textStyle = Type.body(dark),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bindingsText,
                        onValueChange = { bindingsText = it },
                        label = { Text("Member bindings (one per line): Name=participant id") },
                        textStyle = Type.body(dark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Member bindings" },
                        minLines = 3,
                    )
                } else {
                    OutlinedTextField(
                        value = selfId,
                        onValueChange = { selfId = it },
                        label = { Text("Your participant id") },
                        singleLine = true,
                        textStyle = Type.body(dark),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(12.dp))
                // D-213 (fix wave 2): iOS's SecondaryButtonStyle (its words are the file chooser's); a picked file's
                // name keeps its case.
                SecondaryAction(
                    pickedName?.let { "Picked: $it" } ?: "Choose a sheet…",
                    dark,
                    enabled = !busy,
                    keepsCase = pickedName != null,
                    modifier = Modifier,
                ) { openDocument.launch(arrayOf("*/*")) }

                Spacer(Modifier.height(10.dp))
                PrimaryAction(
                    label = "Read this file",
                    dark = dark,
                    enabled = pickedText != null && !busy,
                    onClick = { planSheet() },
                )

                errorText?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(msg, style = Type.body(dark).copy(color = Tok.owed(dark)))
                }
            } else {
                // ---- The review: profile-blind, §9.5's obligations ----
                model.totalsText.forEach { t ->
                    Text("Total $t", style = Type.numericColored(13, Tok.textDim(dark)))
                }
                Spacer(Modifier.height(4.dp))
                Text(Type.kicker("Nothing is created yet"), style = Type.microLabel(dark))
                Spacer(Modifier.height(10.dp))

                SectionLabel("What will be created", dark)
                Spacer(Modifier.height(4.dp))
                model.proposals.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Checkbox(
                            checked = p.included,
                            onCheckedChange = { model.setIncluded(it, forId = p.id) },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(
                                p.noteText ?: "Untitled",
                                style = Type.body(dark).copy(
                                    color = if (p.included) Tok.text(dark) else Tok.textFaint(dark),
                                ),
                            )
                            p.duplicateOf?.let { d ->
                                Text(
                                    Type.kicker("Duplicate: ${d.label}"),
                                    style = Type.microLabel(dark).copy(color = Tok.owed(dark)),
                                )
                            }
                            Row {
                                p.dateText?.let {
                                    Text(it, style = Type.numericColored(12, Tok.textFaint(dark)))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    "${p.amountText} ${p.currencyCode}",
                                    style = Type.numericColored(15, Tok.text(dark)),
                                )
                            }
                        }
                    }
                }

                if (model.rejections.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Refused rows", dark)
                    Spacer(Modifier.height(4.dp))
                    model.rejections.forEach { rejection ->
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text("Line ${rejection.lineNumber}", style = Type.numericColored(12, Tok.textFaint(dark)))
                            Text(rejection.reason, style = Type.bodyDim(dark))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                when (val state = model.appliedState) {
                    is ImportReview.AppliedState.Reviewing -> {
                        PrimaryAction(
                            label = if (model.hasIncludedExpenses) {
                                "Import ${model.includedCount} expenses"
                            } else {
                                "Select at least one expense"
                            },
                            dark = dark,
                            enabled = model.hasIncludedExpenses && !busy,
                            onClick = { applyImport() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Applying writes all selected expenses at once.", style = Type.bodyDim(dark))
                    }
                    is ImportReview.AppliedState.Done -> Text(
                        "${state.expenseCount} expenses now in the ledger",
                        style = Type.body(dark).copy(color = Tok.owing(dark)),
                    )
                    is ImportReview.AppliedState.Failed -> Text(
                        state.message,
                        style = Type.body(dark).copy(color = Tok.owed(dark)),
                    )
                }
            }
        }
    }
}
