package com.vardrlabs.skipti

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import uniffi.skipti_core.LedgerSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §9.2/§9.4's export, through the platform's own file surface (SAF): the
 * user picks where the file lands: "one tap" is part of GUARANTEES #4,
 * not a UI nicety. iOS's ExportScreen, one-to-one where the platform
 * allows: the warning is the WHOLE first screen, §9.4's sentence
 * verbatim, and no byte is written before acknowledgement.
 *
 * The app never routes an export into Photos (iOS excludes the camera
 * roll target outright); SAF's destination is whatever the user picks.
 */
@Composable
fun ExportScreen(
    store: LedgerStore,
    /** D-144: set when opened from Settings (the `--export` proof has none). */
    onBack: (() -> Unit)? = null,
    /** D-210: one group's export (a read-only copy exports only that group);
     *  null is the whole ledger. */
    group: String? = null,
    /** D-249 (U10): "Export Just me": the personal scope only (core's `ffiSnapshotPersonal`), named
     *  `skipti-just-me-YYYY-MM`. */
    justMe: Boolean = false,
) {
    val dark = isSystemInDarkTheme()
    fun read(): LedgerSnapshot = when {
        justMe -> uniffi.skipti_core.ffiSnapshotPersonal(store.snapshot())
        group != null -> store.snapshot(group)
        else -> store.snapshot()
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    // A LedgerSnapshot is not Bundle-shaped: refetched on entry, held for
    // the composition only (the D-113 saver rule applies to custom types).
    var snapshot by remember { mutableStateOf<LedgerSnapshot?>(null) }
    var loadFailed by rememberSaveable { mutableStateOf(false) }
    // D-144: an EMPTY ledger is not an unreadable one (it used to share
    // `loadFailed`, so a first look at Export said "could not be read").
    var isEmpty by rememberSaveable { mutableStateOf(false) }
    var savedName by rememberSaveable { mutableStateOf<String?>(null) }
    var failureText by rememberSaveable { mutableStateOf<String?>(null) }

    fun writeUri(uri: android.net.Uri?, format: Exporter.Format) {
        if (uri == null) return // the user dismissed the picker: nothing written
        // The gate, load-bearing in the screen too: the picker can only
        // have been opened after acknowledgement, and this re-checks it
        // at the one place bytes meet the disk.
        if (!acknowledged) {
            failureText = "Export was not acknowledged."
            return
        }
        // D-235 (Android audit M5): the snapshot is held in memory only. After the picker, a process that was killed
        // while it was open comes back without it: the picker has already made the file, so the snapshot is read again
        // here rather than leaving that file empty without a word.
        val held = snapshot
        scope.launch(Dispatchers.IO) {
            try {
                ExportWrite.write(
                    held = held,
                    load = { read() },
                    format = format,
                    open = { context.contentResolver.openOutputStream(uri) },
                )
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "export"
                withContext(Dispatchers.Main) {
                    savedName = name
                    failureText = null
                    // D-224 (Design 7): the page shows the result; the success haptic is all it adds.
                    Haptics.play(view, HapticKind.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { failureText = "Export failed: $e" }
            }
        }
    }

    val csvCreate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> writeUri(uri, Exporter.Format.CSV) }
    val jsonCreate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> writeUri(uri, Exporter.Format.JSON) }

    fun beginExport(format: Exporter.Format) {
        // §9.4: acknowledgement precedes the picker; the picker's callback
        // is the only place bytes are written.
        acknowledged = true
        val stamp = Exporter.filenameStamp(Exporter.utc())
        val name = if (justMe) Exporter.justMeName(java.time.ZonedDateTime.now(), format) else "${Exporter.FILENAME_PREFIX}-$stamp.${format.fileExtension}"
        if (format == Exporter.Format.CSV) csvCreate.launch(name) else jsonCreate.launch(name)
    }

    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.IO) {
            try {
                read()
            } catch (_: Exception) {
                null
            }
        }
        if (s == null) {
            loadFailed = true
        } else {
            snapshot = s
            isEmpty = s.expenses.isEmpty() && s.settlements.isEmpty() && s.redistributions.isEmpty()
        }
    }

    // D-217: the bar's title is "Export" on every entry (iOS's callers add it; the body keeps its own "Export" label).
    val scroll = rememberScrollState()
    // D-220 (L-D217-3): the page is its bar's backdrop (Aurora blurs it under the bar).
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = BarDecisions.scrollTop(16f).dp, bottom = 16.dp)
        ) {
            if (savedName == null) {
                SectionLabel("Export", dark)
                Spacer(Modifier.height(8.dp))

                // §9.4's sentence, VERBATIM: the whole first screen.
                Text(
                    ExportFlow.WARNING_TEXT,
                    style = Type.body(dark),
                    modifier = Modifier.semantics { contentDescription = ExportFlow.WARNING_TEXT },
                )
                Spacer(Modifier.height(8.dp))
                if (group == null && !justMe) {
                    Text(
                        "Your whole ledger leaves the encrypted store in one file.",
                        style = Type.bodyDim(dark),
                    )
                }
                // §9.4's structural warn-before-export: the loss line is
                // DERIVED from the snapshot, never hand-maintained.
                snapshot?.let { s ->
                    if (ExportFlow.flatLosses(s)) {
                        Spacer(Modifier.height(8.dp))
                        Text(ExportFlow.FLAT_LOSS_TEXT, style = Type.bodyDim(dark))
                    }
                }

                if (loadFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text("The ledger could not be read.", style = Type.body(dark).copy(color = Tok.owed(dark)))
                } else if (isEmpty) {
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing to export yet: no expenses or settlements.", style = Type.bodyDim(dark))
                }

                Spacer(Modifier.height(22.dp))
                // D-213 (fix wave 2): iOS draws both as primary buttons sharing the row, half each.
                Row {
                    PrimaryAction(
                        label = "CSV",
                        dark = dark,
                        enabled = snapshot != null,
                        modifier = Modifier.weight(1f),
                        onClick = { beginExport(Exporter.Format.CSV) },
                    )
                    Spacer(Modifier.width(12.dp))
                    PrimaryAction(
                        label = "JSON backup",
                        dark = dark,
                        enabled = snapshot != null,
                        modifier = Modifier.weight(1f),
                        onClick = { beginExport(Exporter.Format.JSON) },
                    )
                }

                failureText?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(msg, style = Type.body(dark).copy(color = Tok.owed(dark)))
                }
            } else {
                Text("Exported.", style = Type.body(dark))
                Spacer(Modifier.height(8.dp))
                Text(savedName ?: "", style = Type.numericColored(13, Tok.textDim(dark)))
            }
        }
        PushedBar("Export", onBack, scroll = scroll, backdrop = backdrop)
    }
}

/** D-235 (Android audit M5): the bytes of one export into the file the picker made. The snapshot shown ([held]), or a
 *  fresh one ([load]) when the screen lost it; the file opened by [open]. Throws when the ledger cannot be read, the
 *  destination cannot be opened, or the export came out empty, so the screen says "Export failed" and never shows
 *  "Exported." over an empty file. Returns the number of bytes written. */
object ExportWrite {
    class EmptyExport : IllegalStateException("the export came out empty")

    fun write(
        held: uniffi.skipti_core.LedgerSnapshot?,
        load: () -> uniffi.skipti_core.LedgerSnapshot,
        format: Exporter.Format,
        open: () -> java.io.OutputStream?,
    ): Int {
        val s = held ?: load()
        val text = when (format) {
            Exporter.Format.CSV -> uniffi.skipti_core.ffiExportLedgerCsv(s)
            Exporter.Format.JSON -> uniffi.skipti_core.ffiExportLedgerJson(s)
        }
        if (text.isBlank()) throw EmptyExport()
        val bytes = Exporter.fileData(text, format)
        val out = open() ?: throw IllegalStateException("could not open the destination")
        out.use { it.write(bytes) }
        return bytes.size
    }
}
