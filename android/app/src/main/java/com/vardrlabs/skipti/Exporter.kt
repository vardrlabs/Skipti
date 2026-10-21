package com.vardrlabs.skipti

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import uniffi.skipti_core.ExpenseShare
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.PortExpense

/**
 * §9.2's file writer: the shell half of the export story (iOS's
 * Exporter, one-to-one).
 *
 * WHY THE BOM LIVES HERE AND NOWHERE ELSE (incident §5.15): a leading
 * U+FEFF cannot survive the UniFFI lift: core's `write_csv` is pure
 * RFC 4180 by design. §9.2 still wants "UTF-8 with BOM for Excel", so
 * the FILE gets its BOM at write time, in the shell, where bytes are
 * bytes. The JSON envelope takes NO BOM: its identity is `{"format":
 * "skipti-ledger"…` as the first bytes of the document.
 *
 * Filenames follow §9.3's rule: a fixed prefix plus the UTC date, never
 * derived from a group or ledger name: filenames leak into cloud
 * backups and screen shares.
 */
object Exporter {
    enum class Format(val fileExtension: String, val includesBom: Boolean, val mime: String) {
        /** RFC 4180 text from `ffiExportLedgerCsv`. UTF-8 WITH BOM (§9.2). */
        CSV("csv", true, "text/csv"),

        /** The lossless envelope from `ffiExportLedgerJson`. UTF-8, no BOM. */
        JSON("json", false, "application/json"),
    }

    const val FILENAME_PREFIX = "skipti-export"
    /** D-249 (U10): "Export Just me" names its file `skipti-just-me-YYYY-MM.csv` (or .json): fixed text and the month,
     *  never a name (SPEC 9.3), UTC as [filenameStamp]. The twin of iOS's `Exporter.justMeName`. */
    const val JUST_ME_PREFIX = "skipti-just-me-"

    fun justMeName(date: ZonedDateTime, format: Format): String =
        "$JUST_ME_PREFIX${filenameStamp(date).take(7)}.${format.fileExtension}"

    val utf8Bom: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * The exact bytes a written file must contain. Total and testable:
     * [fileData] is what the proof screen and the unit suite both drive,
     * so the shell cannot drift from §5.15's finding.
     */
    fun fileData(text: String, format: Format): ByteArray {
        // A leading U+FEFF in INCOMING text is contamination (a previous
        // export read back as text), never content, for ANY format. JSON's
        // whole identity is its envelope opening bytes. Strip one, add one
        // only for the CSV file (§9.2 Excel):
        val stripped = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val body = stripped.toByteArray(Charsets.UTF_8)
        return if (format.includesBom) utf8Bom + body else body
    }

    /** UTC-fixed both ways: a filename must not depend on which side of a
     *  local midnight the device sits on. */
    fun filenameStamp(date: ZonedDateTime): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(date.toInstant())

    fun utc(date: java.time.Instant = java.time.Instant.now()): ZonedDateTime =
        date.atZone(ZoneOffset.UTC)

    /** Writes the export to a fresh file in [dir] and returns it. The file
     *  is PLAINTEXT (§9.4's warning is the caller's obligation to show
     *  first: this function does not and cannot un-write that fact). */
    fun write(text: String, format: Format, dir: File, date: ZonedDateTime = utc()): File {
        val file = File(dir, "$FILENAME_PREFIX-${filenameStamp(date)}.${format.fileExtension}")
        file.writeBytes(fileData(text, format))
        return file
    }
}

/**
 * §9.4's gate: export writes PLAINTEXT finances outside every guarantee
 * the app makes, so the warning comes FIRST and the write refuses to run
 * without an explicit acknowledgement. The sentence is §9.4's own, word
 * for word: pinned by test; changing it is a spec change, not a copy
 * edit. Structurally, there is no parameter, default, or flag that
 * lowers this gate.
 */
object ExportFlow {
    /** VERBATIM from SPEC §9.4. */
    const val WARNING_TEXT = "This file is not encrypted. Anything you share it with can read it."

    /** Shown with the warning when the flat CSV would silently drop
     *  something the lossless envelope keeps (frozen FX rates, §9.2). */
    const val FLAT_LOSS_TEXT =
        "The flat CSV cannot carry frozen conversion rates. The JSON backup can."

    /** Anything the flat view cannot represent? (§9.4's warn-before-export,
     *  structural: the CSV button's copy and the warning both derive from
     *  THIS, never from a hand-maintained flag.) */
    fun flatLosses(snapshot: LedgerSnapshot): Boolean =
        snapshot.expenses.any { it.conversion != null }

    /** The negative case, pinned: NO file may be written before the
     *  warning has been acknowledged in this session. */
    class NotAcknowledged : Exception("export attempted before acknowledgement")

    /**
     * The write. Structurally refuses an unacknowledged session: there
     * is no parameter, default, or flag that lowers this gate. The
     * flat-loss line (§9.4) is the SCREEN's obligation to render from
     * [flatLosses] before offering CSV; this function refuses only the
     * unacknowledged path, because that gate is the one that must never
     * lower.
     */
    fun export(
        acknowledged: Boolean,
        format: Exporter.Format,
        snapshot: LedgerSnapshot,
        dir: File,
        date: ZonedDateTime = Exporter.utc(),
    ): File {
        if (!acknowledged) throw NotAcknowledged()
        val text = when (format) {
            Exporter.Format.CSV -> uniffi.skipti_core.ffiExportLedgerCsv(snapshot)
            Exporter.Format.JSON -> uniffi.skipti_core.ffiExportLedgerJson(snapshot)
        }
        return Exporter.write(text = text, format = format, dir = dir, date = date)
    }

    /** Proof/test seam (iOS's gateHolds): an UNacknowledged flow must
     *  refuse with the typed error and leave nothing written. */
    fun gateHolds(dir: File): Boolean {
        try {
            export(
                acknowledged = false,
                format = Exporter.Format.CSV,
                snapshot = LedgerSnapshot(groups = emptyList(), expenses = emptyList(), settlements = emptyList(), redistributions = emptyList(), profile = null),
                dir = dir,
            )
            return false // wrote without acknowledgement: gate broken
        } catch (_: NotAcknowledged) {
            return dir.listFiles().isNullOrEmpty()
        } catch (_: Exception) {
            return false // wrong failure mode: the gate is not what refused
        }
    }
}

/**
 * Headless verification (--export-proof): §9.2's export formats written
 * to REAL files by the shell, read back as RAW BYTES, and checked at the
 * platform boundary: §5.15's lesson applied where it bites. Port of
 * iOS's ExportProofScreen; NOT a user flow.
 */
object ExportProof {

    data class Result(
        var csvBomPrefix: Boolean = false,
        var csvRoundTrips: Boolean = false,
        var jsonNoBom: Boolean = false,
        var jsonRoundTrips: Boolean = false,
        var noDoubleBom: Boolean = false,
        var nameShape: Boolean = false,
        var csvExactDecimals: Boolean = false,
    ) {
        val passed: Boolean
            get() = csvBomPrefix && csvRoundTrips && jsonNoBom && jsonRoundTrips &&
                noDoubleBom && nameShape && csvExactDecimals
    }

    /** A tiny real ledger through the real FFI exports (iOS's fixture). */
    fun sampleSnapshot(): LedgerSnapshot {
        val ada = "a".repeat(31) + "1"
        val expense = PortExpense(
            payer = ada,
            paidMinor = 4550,
            currencyCode = "USD",
            group = null,
            conversion = null,
            shares = listOf(ExpenseShare(id = ada, amountMinor = 4550)),
            note = "Dinner, \"seaside\"",
            timestampMs = 20_468uL * 86_400_000uL,
            category = null,
        )
        return LedgerSnapshot(groups = emptyList(), expenses = listOf(expense), settlements = emptyList(), redistributions = emptyList(), profile = null)
    }

    fun prove(dir: File): Result {
        val r = Result()
        val snapshot = sampleSnapshot()
        val csv = uniffi.skipti_core.ffiExportLedgerCsv(snapshot)
        val json = uniffi.skipti_core.ffiExportLedgerJson(snapshot)

        // CSV: BOM prefix then the EXACT RFC 4180 body core produced.
        val csvData = Exporter.fileData(csv, Exporter.Format.CSV)
        if (!csvData.copyOfRange(0, 3).contentEquals(Exporter.utf8Bom)) return r
        if (!csvData.copyOfRange(3, csvData.size).contentEquals(csv.toByteArray(Charsets.UTF_8))) return r
        r.csvBomPrefix = true

        // Exact decimals in the flat view: the amount cell is the plain
        // decimal core rendered, never a float's artefact:
        r.csvExactDecimals = csv.contains("45.50")

        // The written FILE reads back byte-identically; the filename is
        // the fixed prefix plus the UTC date, no ledger names.
        val fixed = "2026-08-26"
        val url = Exporter.write(csv, Exporter.Format.CSV, dir, Exporter.utc(java.time.Instant.parse("2026-08-26T00:00:00Z")))
        if (!url.readBytes().contentEquals(csvData)) return r
        if (url.name != "skipti-export-$fixed.csv") return r
        val lifted = url.readText().removePrefix("\uFEFF")
        r.csvRoundTrips = lifted == csv
        r.nameShape = true

        // JSON: identity bytes, no BOM anywhere, envelope opens the file.
        val jsonData = Exporter.fileData(json, Exporter.Format.JSON)
        if (!jsonData.contentEquals(json.toByteArray(Charsets.UTF_8))) return r
        if (jsonData.copyOfRange(0, 3).contentEquals(Exporter.utf8Bom)) return r
        if (!json.startsWith("{\"format\":\"skipti-ledger\",\"schema_version\":4,")) return r
        r.jsonNoBom = true

        // And the §9.2 envelope re-imports BYTE-IDENTICALLY: the golden
        // both shells pin; a divergence here means the vault format moved.
        val jsonFile = Exporter.write(json, Exporter.Format.JSON, dir)
        if (!jsonFile.readBytes().contentEquals(jsonData)) return r
        val restored = uniffi.skipti_core.ffiImportLedgerJson(jsonFile.readText())
        r.jsonRoundTrips = restored == snapshot

        // A caller handing back BOM'd text (old export re-exported) must
        // never grow a second marker:
        val bommed = "\uFEFF" + csv
        r.noDoubleBom = Exporter.fileData(bommed, Exporter.Format.CSV).contentEquals(csvData)
        return r
    }
}

/** The --export-proof screen: the proof rendered step by step. */
@Composable
fun ExportProofScreen(dir: File) {
    val dark = isSystemInDarkTheme()
    var result by remember { mutableStateOf<ExportProof.Result?>(null) }

    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) { ExportProof.prove(dir) }
    }

    ProofColumn(title = "Export proof", header = "EXPORT FILE WRITING: BYTES ON DISK", dark = dark) {
        ProofRow(dark, "CSV: EF BB BF then exact RFC 4180 body", result?.csvBomPrefix)
        ProofRow(dark, "CSV re-read parses identically (BOM tolerated)", result?.csvRoundTrips)
        ProofRow(dark, "JSON: NO BOM (envelope identity stays first bytes)", result?.jsonNoBom)
        ProofRow(dark, "JSON re-imports byte-identically", result?.jsonRoundTrips)
        ProofRow(dark, "double-BOM handed in is stripped, never doubled", result?.noDoubleBom)
        ProofRow(dark, "filename: fixed prefix + date, no ledger names", result?.nameShape)
        ProofRow(dark, "flat CSV carries exact decimals", result?.csvExactDecimals)
        Verdict(dark, result?.passed)
    }
}

@Composable
internal fun ProofColumn(
    title: String,
    header: String,
    dark: Boolean,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Tok.bg(dark))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(title, style = Type.display(dark))
            Spacer(Modifier.height(12.dp))
            SectionLabel(header, dark)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun ProofRow(dark: Boolean, label: String, value: Boolean?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.body(dark), modifier = Modifier.weight(1f))
        Text(
            when (value) {
                true -> "OK"
                false -> "NO"
                null -> "…"
            },
            style = Type.numericColored(
                14,
                when (value) {
                    true -> Tok.owing(dark)
                    false -> Tok.owed(dark)
                    null -> Tok.textFaint(dark)
                },
            ),
        )
    }
}

@Composable
internal fun Verdict(dark: Boolean, passed: Boolean?) {
    if (passed == null) return
    Spacer(Modifier.height(12.dp))
    Text(
        Type.kicker(if (passed) "PROOF PROVEN" else "PROOF FAILED"),
        style = Type.microLabel(dark).copy(
            color = if (passed) Tok.owing(dark) else Tok.owed(dark),
        ),
    )
}
