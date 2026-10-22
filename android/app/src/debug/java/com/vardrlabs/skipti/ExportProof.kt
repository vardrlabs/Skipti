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
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.ExpenseShare
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.PortExpense

// D-260 (G-13, decision 269): a proof surface moved out of src/main, Debug builds only (src/release has none).

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
