package com.vardrlabs.skipti

import android.content.Context
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import java.util.Locale
import uniffi.skipti_core.ffiCurrencyCodes

/**
 * D-165 (SPEC §5.2): the default currency, and every currency core knows.
 *
 * SPEC §5.2 asks for "Full ISO 4217, with INR, USD, EUR, GBP, JPY, AED, SGD,
 * AUD, CAD promoted in the picker"; until now both shells offered the nine
 * and nothing else. The full list is core's own (`ffiCurrencyCodes`), so a
 * person can pick exactly what core can store, on both shells.
 *
 * The default currency only pre-selects the currency of a new group and of a
 * fresh expense. D-244 (ADR-0043): once chosen, it follows the account to its
 * other phones (`SettingsSync`; a code this build's core does not know is not
 * applied); the region's default is never synced. Unset, it
 * is the phone region's currency when core knows it, else USD. Money
 * formatting still follows the record's currency and the locale (D-50).
 * The twin of ios/Skipti/App/CurrencyChoice.swift.
 */
object DefaultCurrency {
    // D-244 (ADR-0043): internal, so SettingsSync's table names where the choice is kept.
    internal const val PREFS = "skipti-money"
    internal const val KEY = "default-currency"
    const val FALLBACK = "USD"

    /** Core's list, sorted. It cannot change while the app runs. */
    val all: List<String> by lazy { ffiCurrencyCodes() }
    private val known: Set<String> by lazy { all.toSet() }

    /** The stored choice (null until one is made); D-221's currency step reads it. */
    var stored by mutableStateOf<String?>(null)
        private set

    /** A stored choice core knows, else the region's currency core knows,
     *  else USD. A stored code this build does not know never sticks. */
    fun resolve(stored: String?, region: String?, known: Set<String>): String = when {
        stored != null && stored in known -> stored
        region != null && region in known -> region
        else -> FALLBACK
    }

    /** The phone region's currency, when the locale has a country. */
    fun regionCurrency(locale: Locale = Locale.getDefault()): String? =
        runCatching { java.util.Currency.getInstance(locale)?.currencyCode }.getOrNull()

    val current: String
        get() = resolve(stored, regionCurrency(), known)

    /** U26 (the founder, 2026-10-20): until a currency core knows is stored, nothing has been chosen, so the list
     *  ticks nothing and offers the code [current] resolves to (the region's, else USD) first, under "Suggested". Null
     *  once a choice is stored. The same "chosen" as D-221's currency step. The twin of iOS's `suggestion`. */
    fun suggestion(stored: String?, region: String?, known: Set<String>): String? =
        if (stored != null && stored in known) null else resolve(null, region, known)

    fun suggestion(): String? = suggestion(stored, regionCurrency(), known)

    fun load(context: Context) {
        stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }

    /** D-244 (ADR-0043): through SettingsSync.set, the one write of a synced choice: stored as before, [stored] reloaded,
     *  and with an account the setting record that carries it to the account's other phones. */
    fun set(context: Context, code: String) {
        SettingsSync.set(context, SettingsSync.CURRENCY, code)
    }

    /** The short list a picker shows: the promoted nine in SPEC's order, then
     *  any of [extra] not among them (the default, the current choice), once. */
    fun choices(extra: List<String>): List<String> =
        (AddExpenseUi.promotedCurrencies + extra).distinct()
}

object CurrencyCopy {
    const val SECTION = "Currency"
    const val LABEL = "Default currency"
    const val DETAIL = "New groups and new expenses start in this currency. You can pick another each time."
    const val ALL_CURRENCIES = "All currencies"
    const val COMMON = "Common"
    /** U26: the label over the one suggested code while nothing is chosen. */
    const val SUGGESTED = "Suggested"
    const val EVERYTHING = "All currencies"
    const val SEARCH = "Code or name"
    const val NO_MATCH = "No currency matches."
    const val CLOSE = "Close"

    /** The phone's name for a code, when it has one ("Indian Rupee"). */
    fun name(code: String, locale: Locale = Locale.getDefault()): String? =
        runCatching { java.util.Currency.getInstance(code).getDisplayName(locale) }
            .getOrNull()
            ?.takeIf { it != code }

    /** Search by code or name, ignoring case and accents. */
    fun matches(code: String, name: String?, query: String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        return fold(code).contains(q) || (name != null && fold(name).contains(q))
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}

/**
 * The currency chips every picker shows: the short list plus an "All
 * currencies" chip that opens the searchable list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CurrencyChips(
    selected: String,
    onSelect: (String) -> Unit,
    dark: Boolean,
) {
    var listing by remember { mutableStateOf(false) }
    val colors = chipColors(dark)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DefaultCurrency.choices(listOf(DefaultCurrency.current, selected)).forEach { code ->
            FilterChip(
                selected = selected == code,
                onClick = { onSelect(code) },
                label = { Text(code) },
                colors = colors,
            )
        }
        FilterChip(
            selected = false,
            onClick = { listing = true },
            label = { Text(CurrencyCopy.ALL_CURRENCIES) },
            colors = colors,
        )
    }
    if (listing) {
        CurrencyListDialog(
            selected = selected,
            dark = dark,
            onPick = {
                onSelect(it)
                listing = false
            },
            onClose = { listing = false },
        )
    }
}

/** Every currency core knows, searchable; tapping one picks it. D-220 (L-D217-6): iOS pushes this list inside the
 *  sheet that opened it; Android keeps a window of its own (it opens from several sheets) but draws it as a pushed
 *  page: D-225 (Design 10) moved that window into [PushedWindow], which the choice pages share. Inside it: the search
 *  in its own card row, "Common" and "All currencies" as section labels over cards of rows. */
@Composable
fun CurrencyListDialog(
    selected: String,
    dark: Boolean,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    /** U26: nothing chosen yet: no row is ticked, and this code leads under "Suggested" ([DefaultCurrency.suggestion]). */
    suggested: String? = null,
) {
    var query by remember { mutableStateOf("") }
    // D-220 fix wave (I8): filtered once per query, not on every recomposition (the list is about 160 rows).
    val filtered = remember(query) { DefaultCurrency.all.filter { CurrencyCopy.matches(it, CurrencyCopy.name(it), query) } }
    PushedWindow(CurrencyCopy.ALL_CURRENCIES, dark, onClose) {
        CurrencyListBody(query, { query = it }, filtered, if (suggested == null) selected else null, suggested, dark, onPick)
    }
}

/** The list's content, laid out by [PushedWindow]'s column (8 apart). */
@Composable
private fun CurrencyListBody(
    query: String,
    onQuery: (String) -> Unit,
    filtered: List<String>,
    selected: String?,
    suggested: String?,
    dark: Boolean,
    onPick: (String) -> Unit,
) {
    // A plain field in its own card row, as iOS's (not a system search bar).
    Row(Modifier.glassCard(dark), verticalAlignment = Alignment.CenterVertically) {
        Icon(TabIcons.Search, contentDescription = null, tint = Tok.textDim(dark), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        val style = Type.body(dark)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Tok.accent(dark)),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).semantics { contentDescription = CurrencyCopy.SEARCH },
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) Text(CurrencyCopy.SEARCH, style = style.copy(color = Tok.textFaint(dark)))
                    field()
                }
            },
        )
    }
    // As iOS's `query.isEmpty`: a space is a query, so Common hides for it too.
    if (query.isEmpty() && suggested != null) {
        SectionLabel(CurrencyCopy.SUGGESTED, dark, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        CurrencyCard(listOf(suggested), selected, dark, onPick)
    }
    if (query.isEmpty()) {
        SectionLabel(CurrencyCopy.COMMON, dark, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        CurrencyCard(AddExpenseUi.promotedCurrencies, selected, dark, onPick)
    }
    SectionLabel(CurrencyCopy.EVERYTHING, dark, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
    if (filtered.isEmpty()) {
        Text(CurrencyCopy.NO_MATCH, style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark))
    } else {
        CurrencyCard(filtered, selected, dark, onPick)
    }
}

/** A card of currency rows with hairline dividers (iOS's grouped list section). */
@Composable
private fun CurrencyCard(codes: List<String>, selected: String?, dark: Boolean, onPick: (String) -> Unit) {
    Column(Modifier.glassCard(dark)) {
        // D-220 fix wave (I8): keyed by code, so a new query moves rows instead of recomposing every one. The card stays
        // one column (a card of rows cannot span lazy items without per-row card segments; the cost is about 160 rows
        // composed once per query).
        codes.forEachIndexed { i, code ->
            key(code) {
                if (i > 0) HorizontalDivider(color = Tok.hairline(dark))
                CurrencyRow(code, selected, dark, onPick)
            }
        }
    }
}

@Composable
private fun CurrencyRow(code: String, selected: String?, dark: Boolean, onPick: (String) -> Unit) {
    val name = CurrencyCopy.name(code)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(code) }
            .padding(vertical = 11.dp)
            .semantics { contentDescription = listOfNotNull(code, name, "selected".takeIf { code == selected }).joinToString(", ") },
    ) {
        // iOS: the code at numeric 17 (400), the name at the detail size in the dim colour, the accent check.
        Text(code, style = Type.numeric(17, FontWeight.Normal, dark).copy(color = Tok.text(dark)))
        if (name != null) {
            Text(name, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp), modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (code == selected) Icon(TabIcons.Check, contentDescription = null, tint = Tok.accent(dark), modifier = Modifier.size(20.dp))
    }
}
