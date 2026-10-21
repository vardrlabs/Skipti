package com.vardrlabs.skipti

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.skipti_core.skiptiCoreVersion

/**
 * D-146: About, and the licences of everything SKIPTI is built from, the
 * twin of iOS's `About.swift`. The founder (2026-09-21) asked for "things
 * an actual app usually has": the version, who makes it, what it promises,
 * what it does with your data, where to report a security problem, and the
 * licences.
 *
 * Every privacy sentence passes SPEC §14.6: true given the threat model,
 * still true if the reader is a malicious operator, and it states its own
 * limit next to it (the metadata the server learns, THREAT_MODEL.md).
 */
/**
 * D-176: the certificates this installed app is signed with, shown in About so
 * Google sign-in can be matched to them (2026-09-26: Play moved SKIPTI to a
 * quantum-ready signing key on its own, and the installed build's certificate
 * was in no OAuth client). Public information: any installed APK reveals it.
 */
object SigningCert {
    /** Upper-case hex pairs joined by colons, the form Play Console and
     *  Google Cloud show. */
    fun fingerprint(der: ByteArray, algorithm: String): String =
        java.security.MessageDigest.getInstance(algorithm).digest(der).joinToString(":") { "%02X".format(it) }

    /** Oldest first; the last is the certificate signing this install now.
     *  More than one means Play rotated the key (Android 9+ keeps the line). */
    fun history(context: Context): List<ByteArray> = runCatching {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return@runCatching emptyList()
            val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            certs.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray() } ?: emptyList()
        }
    }.getOrDefault(emptyList())
}

object AboutInfo {
    const val MAKER = "Made by Vardr Labs"
    const val SECURITY_EMAIL = "security@vardrlabs.com"

    /** GUARANTEES.md 1 to 7, in its order, shortened for a phone screen. */
    val PROMISES = listOf(
        "No subscription, no ads, no telemetry, no analytics. Ever.",
        "No limit on logging expenses: no caps, meters or waiting.",
        "No feature is behind a payment. There is no paid tier.",
        "Export all your data, in an open format, any time, in one tap.",
        "Your own ledger works fully offline and without an account. You are never pushed to make one; groups need one.",
        "The app talks only to SKIPTI's cloud, which runs on Google Firebase, and to Apple or Google to sign you in and deliver notifications.",
        "Only you can read your ledger, and only a group's members can read the group. It is not end-to-end encrypted.",
    )

    val PRIVACY = listOf(
        "Your ledger is kept on this phone in an encrypted database. Anyone who can unlock this phone can open SKIPTI.",
        "Without an account, SKIPTI never sends your ledger anywhere. It leaves this phone only when you export it.",
        "With an account, your ledger and your groups are kept in SKIPTI's cloud on Google Firebase, in the United States, so your other phones and your groups' members get them. Only you, and each group's members, can read them. They are encrypted in transit and at rest, but not end to end: Google, as the host, and Vardr Labs, as the operator, could technically access them.",
        "If you sign in with Google or Apple, they learn that you use SKIPTI.",
        // D-196: push (the Firebase Installations ID behind FCM's token).
        "With an account and sync on, Firebase gives this phone an address for notifications, kept with your account. Each notification says who added, changed or deleted which expense and the amount, and Apple or Google delivers it.",
        // D-197: being found, and contacts matching.
        "Unless you switch it off in Settings, people who know your username or email can find you. If you use Find friends in contacts, each contact's email becomes a fingerprint on this phone; only the fingerprints are sent, and SKIPTI's cloud keeps none.",
        "SKIPTI has no ads, trackers or analytics, and it does not report what you do in the app.",
    )

    const val SECURITY_NOTE = "Found a security problem? Email us. We acknowledge every report within 48 hours."

    /** SPEC §13.5: an honest disclaimer. */
    const val DISCLAIMER = "SKIPTI is not a bank, an accountant or a tax adviser, and nothing in it is " +
        "financial advice. It is provided as is, without warranty of any kind."

    fun versionLine(app: String, build: String) = "Version $app ($build)"

    fun coreLine(core: String) = "Core $core"

    /** This build's versionName and versionCode, from the package manager. */
    fun current(context: Context): Pair<String, String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return (info.versionName ?: "?") to code.toString()
    }
}

// --- The licence inventory (scripts/licences.py writes it) -----------------

@Serializable
data class LicenceComponent(
    val group: String,
    val name: String,
    val version: String,
    val licence: String,
    val offered: String? = null,
    val shells: List<String>,
    val notice: String? = null,
    val texts: List<String>,
)

@Serializable
data class LicenceInventory(
    val format: Int,
    val groups: List<String>,
    val components: List<LicenceComponent>,
    val texts: Map<String, String>,
)

data class LicenceSection(val group: String, val items: List<LicenceComponent>)

object Licences {
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Licences that carry no text to show: SQLite is public domain, and
     *  Google's artwork and libraries come under Google's own terms, which
     *  the entry names. */
    val TEXTLESS = setOf(
        "Public domain", "Google brand asset", "Android Software Development Kit License",
        // D-191: Google libraries Firebase Auth pulls, under Google's own terms
        // (named with a link, as the Android SDK licence is).
        "Play Core Software Development Kit Terms of Service", "Play Integrity API Terms of Service",
    )

    /** This build's name in the inventory's `shells` lists: one Android
     *  build since D-191 retired the `direct` flavour. */
    const val SHELL = "play"

    fun parse(json: String): LicenceInventory = JSON.decodeFromString(json)

    fun load(context: Context): LicenceInventory? = try {
        parse(context.assets.open("licences.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        null
    }

    /** What one shell shows, in the inventory's group order, empty groups left out. */
    fun sections(inventory: LicenceInventory, shell: String): List<LicenceSection> =
        inventory.groups.mapNotNull { group ->
            val items = inventory.components.filter { it.group == group && shell in it.shells }
            if (items.isEmpty()) null else LicenceSection(group, items)
        }

    fun texts(component: LicenceComponent, inventory: LicenceInventory): List<String> =
        component.texts.mapNotNull { inventory.texts[it] }

    private val ITEM = Regex("^(\\*|-|•|\\(?[0-9A-Za-z]{1,3}[.)])\\s")

    /** Licence files are hard-wrapped at about 80 columns; on a phone the
     *  screen wraps them again and every line breaks twice. This joins the
     *  lines of a paragraph, and keeps paragraphs and list items (`*`, `-`,
     *  `1.`, `(a)`) on their own lines. The words are untouched. */
    fun reflow(text: String): String {
        val paragraphs = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (raw in text.split("\n")) {
            val line = raw.trim(' ', '\t')
            if (line.isEmpty()) {
                if (lines.isNotEmpty()) {
                    paragraphs += lines.joinToString("\n")
                    lines.clear()
                }
                continue
            }
            if (lines.isEmpty() || ITEM.containsMatchIn(line)) lines += line else lines[lines.size - 1] += " $line"
        }
        if (lines.isNotEmpty()) paragraphs += lines.joinToString("\n")
        return paragraphs.joinToString("\n\n")
    }

    /** Everything wrong with an inventory; empty when it is sound. */
    fun problems(inventory: LicenceInventory): List<String> {
        val out = mutableListOf<String>()
        val known = setOf("ios", "play")
        for (c in inventory.components) {
            for (key in c.texts) if (inventory.texts[key] == null) out += "${c.name}: text $key is missing"
            if (c.texts.isEmpty() && c.licence !in TEXTLESS) out += "${c.name}: no licence text for ${c.licence}"
            if (c.group !in inventory.groups) out += "${c.name}: unknown group ${c.group}"
            if (c.shells.isEmpty() || !known.containsAll(c.shells)) {
                out += "${c.name}: bad shells ${c.shells.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}"
            }
        }
        return out
    }
}

// --- Screens -----------------------------------------------------------------

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicences: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val version = remember { AboutInfo.current(context) }
    var mailError by remember { mutableStateOf(false) }

    PushedPage("About", dark, onBack, side = BarDecisions.PAGE_SIDE_WIDE.dp) {
        AboutCard(dark) {
            Text("SKIPTI", style = Type.display(dark).copy(fontSize = TypeScale.wordmark.sp))
            Text(AboutInfo.MAKER, style = Type.body(dark))
            Text(AboutInfo.versionLine(version.first, version.second), style = Type.numeric(14, FontWeight.Normal, dark).copy(color = Tok.textDim(dark)))
            Text(AboutInfo.coreLine(skiptiCoreVersion()), style = Type.numeric(14, FontWeight.Normal, dark).copy(color = Tok.textDim(dark)))
        }

        // D-176: which certificate signs this install (for Google sign-in).
        val certs = remember { SigningCert.history(context) }
        if (certs.isNotEmpty()) {
            AboutSection("App signature", dark)
            // Android's own card (no twin): its keys 12 apart, each label right over its fingerprints.
            AboutCard(dark, spacing = 12.dp) {
                certs.asReversed().forEachIndexed { index, der ->
                    Column {
                        SectionLabel(if (index == 0) "Signing this install" else "Earlier key", dark)
                        SelectionContainer {
                            Column {
                                Text("SHA-1  " + SigningCert.fingerprint(der, "SHA-1"), style = Type.numeric(12, FontWeight.Normal, dark))
                                Text("SHA-256  " + SigningCert.fingerprint(der, "SHA-256"), style = Type.numeric(12, FontWeight.Normal, dark).copy(color = Tok.textDim(dark)))
                            }
                        }
                    }
                }
            }
        }

        AboutSection("Promises", dark)
        AboutCard(dark) {
            AboutInfo.PROMISES.forEachIndexed { index, promise ->
                Row {
                    Text(
                        "${index + 1}",
                        style = Type.numeric(14, FontWeight.Normal, dark).copy(color = Tok.textDim(dark)),
                        modifier = Modifier.widthIn(min = 22.dp),
                    )
                    Text(promise, style = Type.body(dark).copy(fontSize = TypeScale.prose.sp))
                }
            }
        }

        AboutSection("Privacy", dark)
        AboutCard(dark) {
            AboutInfo.PRIVACY.forEach { line ->
                Text(line, style = Type.body(dark).copy(fontSize = TypeScale.prose.sp))
            }
        }

        AboutSection("Security", dark)
        AboutCard(dark) {
            Text(AboutInfo.SECURITY_NOTE, style = Type.body(dark).copy(fontSize = TypeScale.prose.sp))
            SelectionContainer {
                Text(AboutInfo.SECURITY_EMAIL, style = Type.numeric(14, FontWeight.Normal, dark).copy(color = Tok.textDim(dark)))
            }
            SecondaryAction("Report a security problem", dark) {
                mailError = !openMail(context)
            }
            if (mailError) {
                Text(
                    "No email app is set up on this phone. Write to ${AboutInfo.SECURITY_EMAIL}.",
                    style = Type.bodyDim(dark).copy(color = Tok.owed(dark)),
                )
            }
        }

        AboutSection("Legal", dark)
        AboutCard(dark) {
            Text(AboutInfo.DISCLAIMER, style = Type.body(dark).copy(fontSize = TypeScale.prose.sp))
            SecondaryAction("Licences", dark, onOpenLicences)
        }
    }
}

private fun openMail(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + AboutInfo.SECURITY_EMAIL)))
    true
} catch (e: ActivityNotFoundException) {
    false
}

@Composable
fun LicencesScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val inventory = remember { Licences.load(context) }
    val sections = remember(inventory) {
        inventory?.let { Licences.sections(it, Licences.SHELL) }
    }

    // D-217: a long list, so the bar takes the lazy list's state for its ground.
    val list = rememberLazyListState()
    // D-220 (L-D217-3): the page is its bar's backdrop (Aurora blurs it under the bar).
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        LazyColumn(
            Modifier.fillMaxSize().backdropSource(backdrop),
            state = list,
            contentPadding = PaddingValues(start = BarDecisions.PAGE_SIDE_WIDE.dp, end = BarDecisions.PAGE_SIDE_WIDE.dp, top = BarDecisions.scrollTop(20f).dp),
        ) {
            item {
                Text(
                    "SKIPTI is built on other people's work. These are the fonts, drawings and " +
                        "libraries in this app, and the licences they come under. Tap one to read it.",
                    style = Type.bodyDim(dark).copy(fontSize = TypeScale.prose.sp),
                )
                if (sections == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "The licence list could not be read.",
                        style = Type.body(dark).copy(color = Tok.owed(dark)),
                    )
                }
            }
            // D-225 (Design 6; L73): per section its label and one list card of list rows (the name over version and
            // licence, the chevron); one lazy item per section (a card cannot span lazy items).
            var index = 0
            for (section in sections.orEmpty()) {
                val start = index
                item(key = "s/" + section.group) {
                    AboutSection(section.group, dark)
                    ListCard(dark) {
                        section.items.forEachIndexed { i, component ->
                            row {
                                ListRow(
                                    component.name, dark,
                                    parts = listOfNotNull(component.version.takeIf { it.isNotEmpty() }, component.licence),
                                    trailing = RowTrailing.Chevron,
                                ) { onOpen(start + i) }
                            }
                        }
                    }
                }
                index += section.items.size
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
        PushedBar("Licences", onBack, lazy = list, backdrop = backdrop)
    }
}

/** The shown components in list order, so a route can carry an index. */
fun licenceAt(context: Context, index: Int): Pair<LicenceComponent, List<String>>? {
    val inventory = Licences.load(context) ?: return null
    val all = Licences.sections(inventory, Licences.SHELL).flatMap { it.items }
    val c = all.getOrNull(index) ?: return null
    return c to Licences.texts(c, inventory)
}

@Composable
fun LicenceDetailScreen(index: Int, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val entry = remember(index) { licenceAt(context, index) }

    // D-225 fix wave (F2): its lines 12 apart, iOS's VStack spacing (they were 8, with a gap even without a version).
    PushedPage(entry?.first?.name ?: "", dark, onBack, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp) {
        if (entry == null) {
            Text("The licence list could not be read.", style = Type.body(dark).copy(color = Tok.owed(dark)))
            return@PushedPage
        }
        val (component, texts) = entry
        if (component.version.isNotEmpty()) {
            Text("Version " + component.version, style = Type.numeric(14, FontWeight.Normal, dark).copy(color = Tok.textDim(dark)))
        }
        Text("Licence: " + component.licence, style = Type.body(dark).copy(fontSize = TypeScale.prose.sp))
        val offered = component.offered
        if (offered != null && offered != component.licence) {
            Text(
                "Offered under $offered. SKIPTI uses it under ${component.licence}.",
                style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
            )
        }
        component.notice?.let {
            Text(it, style = Type.body(dark).copy(fontSize = TypeScale.prose.sp))
        }
        for (text in texts) {
            SelectionContainer {
                // D-225 (Design 6): the licence text on a glass card padded 12, at the footnote size.
                Text(
                    Licences.reflow(text),
                    style = Type.bodyDim(dark).copy(fontSize = TypeScale.footnote.sp),
                    modifier = Modifier.glassCard(dark, padding = 12),
                )
            }
        }
    }
}

// --- Shared pieces (the section label and card; SecondaryAction is GroupScreens')

/** D-225 (Design 2, L70): a section's label alone, 8 above its card (the hairline under it went). */
@Composable
private fun AboutSection(title: String, dark: Boolean) {
    Spacer(Modifier.height(20.dp))
    SectionLabel(title, dark, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
}

/** D-225 (Design 6, L71): About's cards are glass cards, as every other page's. D-225 fix wave (F1): their lines
 *  [spacing] 10 apart, as iOS's `card` VStack spaces them (the first card was 6, 6, 0: 133.7 dp against 152 pt). */
@Composable
private fun AboutCard(dark: Boolean, spacing: Dp = 10.dp, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(dark), verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
}
