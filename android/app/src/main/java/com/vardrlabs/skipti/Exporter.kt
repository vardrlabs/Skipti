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
    /** D-249 (U10): "Export Just me" names its file `skipti-just-me-YYYY-MM-DD.csv` (or .json): fixed text and the day
     *  it was made, never a name (SPEC 9.3). Decision 207: the phone's own date ([date]'s zone; the screen passes
     *  [ZonedDateTime.now]), not UTC, and the day, not a month (the file holds every Just me month). The twin of iOS's
     *  `Exporter.justMeName`. */
    const val JUST_ME_PREFIX = "skipti-just-me-"

    fun justMeName(date: ZonedDateTime, format: Format): String =
        "$JUST_ME_PREFIX${DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.ROOT).format(date)}.${format.fileExtension}"

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
