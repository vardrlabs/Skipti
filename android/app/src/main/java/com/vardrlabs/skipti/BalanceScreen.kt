package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiMoneyFormat
import uniffi.skipti_core.ffiMoneyFormatDecomposition
import uniffi.skipti_core.ffiMoneySpoken

/**
 * §11.4's signature line, computed data: a pure decision layer the JVM unit
 * tests drive against the REAL core through the same JNA bindings the app
 * uses (no mocks, no android.jar). Every money string comes from core FFI:
 * ffiMoneyFormat / ffiMoneyFormatDecomposition / ffiMoneySpoken, never a
 * Kotlin NumberFormat, never string building (D-46's rule, AppRoot's header).
 */
object BalanceUi {
    /** §11.6's empty-state copy, verbatim. */
    const val EMPTY_LEDGER_COPY =
        "Nothing here yet. Add your first expense."

    enum class State { LOADING, EMPTY, CONTENT }

    fun stateFor(loadedLines: List<BalanceLineOut>?): State = when {
        loadedLines == null -> State.LOADING
        loadedLines.isEmpty() -> State.EMPTY
        else -> State.CONTENT
    }

    /**
     * ONE LINE PER CURRENCY, biggest absolute net first (D-50: the UI
     * discards nothing); ties by currency code, mirroring the Swift sort.
     */
    fun sortedLines(lines: List<BalanceLineOut>): List<BalanceLineOut> =
        lines.sortedWith(
            compareByDescending<BalanceLineOut> { kotlin.math.abs(it.viewerNetMinor) }
                .thenByDescending { it.currencyCode }
        )

    /** §5.2: the frozen rate's age in whole days; null when nothing converted. */
    fun stalenessDays(nowMs: Long, expenses: List<ExpenseInput>): Int? =
        expenses
            .mapNotNull { it.conversion?.rateTimestampMs?.toLong() }
            .minOrNull()
            ?.let { oldest ->
                ((nowMs - oldest) / (24L * 60L * 60L * 1000L)).coerceAtLeast(0).toInt()
            }

    /** Positive = money comes to you (D-47's viewer frame), signed out of core. */
    fun captionFor(netMinor: Long): String = when {
        netMinor > 0 -> "you are owed"
        netMinor < 0 -> "you owe"
        else -> "settled"
    }

    /** §11.2's money colours; the sign is always printed alongside (never colour-only). */
    fun tokenFor(minor: Long): Tok = when {
        minor > 0 -> Tok.owing
        minor < 0 -> Tok.owed
        else -> Tok.neutral
    }

    /** §5.2: the settlement currency is named in the micro-label, with the group. */
    fun headerFor(line: BalanceLineOut, groupName: String?, memberCount: Int): String =
        "${groupName ?: "Goa trip"}${Separator.meta()}${if (memberCount == 1) "1 member" else "$memberCount members"}${Separator.meta()}" +
            "settles in ${line.currencyCode}"

    fun spokenHeaderFor(line: BalanceLineOut, groupName: String?, memberCount: Int): String =
        "${groupName ?: "Goa trip"}, $memberCount members, settled in ${line.currencyCode}"

    /**
     * ALL money strings route through core. A failure is a programming bug
     * (bad currency), never a user condition: the screen shows
     * [Placeholder.NONE], and the spoken amount is empty, as on iOS (D-228
     * decision 43), so TalkBack never reads the hyphen as a minus.
     */
    fun moneyFormat(minor: Long, currencyCode: String, locale: LocaleTag): String =
        try { ffiMoneyFormat(minor, currencyCode, locale) } catch (_: Exception) { Placeholder.NONE }

    fun moneyDecomposition(minor: Long, currencyCode: String, locale: LocaleTag): String =
        try { ffiMoneyFormatDecomposition(minor, currencyCode, locale) } catch (_: Exception) { Placeholder.NONE }

    fun moneySpoken(minor: Long, currencyCode: String, locale: LocaleTag): String =
        try { ffiMoneySpoken(minor, currencyCode, locale) } catch (_: Exception) { "" }

    /** The rendered decomposition for one line, in core's order. */
    fun renderedDecompositions(line: BalanceLineOut, locale: LocaleTag): List<String> =
        line.entries.map { moneyDecomposition(it.minorUnits, line.currencyCode, locale) }
}

/**
 * §11.4's signature: the amount, "you are owed", a hairline rule, then the
 * per-person decomposition INLINE: one glance answers "how much" and "from
 * whom". Port of ios/Skipti/App/BalanceLineView.swift. Content stays opaque
 * (ADR-0023 §7), so §11.7's per-pair contrast verification holds; TalkBack
 * labels come from ffiMoneySpoken, never from the visible string.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BalanceLineView(
    line: BalanceLineOut,
    names: Map<String, String>,
    viewerID: String,
    dark: Boolean,
) {
    val net = line.viewerNetMinor
    val locale = AppLocale.current
    // Android's isAccessibilitySize: at elevated font scales the caption
    // drops BELOW the amount: forcing them side-by-side is exactly where
    // §11.7 said this screen breaks ("you / are / owed" in a ragged
    // column). The threshold mirrors iOS's own curve: AX1 is ~1.18x of
    // largeTitle, and Android's 1.1/1.2 steps sit in that same band.
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale >= 1.15f

    Column(Modifier.fillMaxWidth()) {
        // The amount (the numbers role since D-212) rides iOS's own curve (BalanceLineTests pins
        // largeTitle at 1.7x at the largest size): scaled, then capped
        // where the string stops fitting its card. The first 200% screenshot
        // caught the uncapped version clipping MID-DIGIT ("-₹2,182." with
        // the 50 gone), the exact defect class §11.7 names; the cap is what
        // keeps every digit on screen. Known limit, recorded honestly: a
        // much longer headline could still overflow one line at 200%:
        // width-aware shrinking (iOS's minimumScaleFactor) is §4 material.
        val amountStyle = Type.numeric(40, dark = dark)
            .copy(color = BalanceUi.tokenFor(net)(dark))
            .copy(fontSize = (40 * min(fontScale, 1.4f)).sp)

        val amount: @Composable (Modifier) -> Unit = { mod ->
            Text(
                BalanceUi.moneyFormat(net, line.currencyCode, locale),
                style = amountStyle,
                maxLines = 1,
                modifier = mod.semantics {
                    contentDescription = BalanceUi.moneySpoken(net, line.currencyCode, locale)
                },
            )
        }
        val caption: @Composable (Modifier) -> Unit = { mod ->
            Text(
                BalanceUi.captionFor(net),
                style = Type.bodyDim(dark),
                // Hidden from accessibility: the spoken label already says it.
                modifier = mod.clearAndSetSemantics {},
            )
        }

        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                amount(Modifier)
                caption(Modifier)
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                // Both opt in: a lone alignByBaseline child becomes the
                // row's baseline itself, which parks the caption at the
                // amount's TOP: iOS aligns .firstTextBaseline, and so
                // does this row once both declare.
                amount(Modifier.alignByBaseline())
                // D-221 (L-D217-7): the caption at the row's end, as iOS's `Spacer(minLength: 12)` puts it; the
                // padding keeps the 12 dp minimum when the amount fills the row.
                Spacer(Modifier.weight(1f))
                caption(Modifier.alignByBaseline().padding(start = 12.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Tok.hairline(dark))
        )

        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (stacked) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (stacked) 14.dp else 18.dp),
        ) {
            line.entries.forEach { entry ->
                val name = names[entry.id] ?: entry.id
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "$name: " +
                            BalanceUi.moneySpoken(entry.minorUnits, line.currencyCode, locale)
                    },
                ) {
                    Text(
                        Type.kicker(name),
                        style = Type.microLabel(dark).copy(color = Tok.textDim(dark)),
                    )
                    Text(
                        BalanceUi.moneyDecomposition(entry.minorUnits, line.currencyCode, locale),
                        style = Type.numericColored(15, Tok.text(dark), weight = FontWeight.Normal),
                    )
                }
            }
        }
    }
}
