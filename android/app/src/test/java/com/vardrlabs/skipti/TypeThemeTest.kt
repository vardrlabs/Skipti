package com.vardrlabs.skipti

import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-212 (ADR-0041): each theme's faces, the twin of iOS TypeThemeTests. */
class TypeThemeTest {
    @After
    fun reset() = Appearance.setForTest()

    @Test
    fun each_role_draws_its_themes_face() {
        val expected = mapOf(
            Role.DISPLAY to listOf(Face.GROTESK, Face.CORMORANT, Face.GROTESK),
            Role.BODY to listOf(Face.SANS, Face.SANS, Face.GROTESK),
            Role.NUMERIC to listOf(Face.GROTESK, Face.GROTESK, Face.PLEX),
            Role.LABEL to listOf(Face.SANS, Face.SANS, Face.PLEX),
            Role.MICRO to listOf(Face.SANS, Face.CORMORANT, Face.PLEX),
        )
        for (role in Role.entries) for ((i, theme) in AppTheme.entries.withIndex()) {
            assertEquals("$role $theme", expected.getValue(role)[i], Type.face(role, theme))
        }
    }

    /** F5: a tab's label is drawn in the BODY face in every theme, never uppercase or tracked. */
    @Test
    fun the_tab_label_draws_the_body_face_in_every_theme() {
        for (theme in AppTheme.entries) {
            assertEquals("$theme", Type.face(Role.BODY, theme), Type.tabFace(theme))
            Appearance.setForTest(theme = theme)
            val style = Type.tabLabel(false)
            assertEquals("$theme family", Type.body(false).fontFamily, style.fontFamily)
            assertEquals("$theme weight", androidx.compose.ui.text.font.FontWeight(600), style.fontWeight)
            assertEquals("$theme size", 11.0f, style.fontSize.value, 0.001f)
            assertEquals("$theme tracking", androidx.compose.ui.unit.TextUnit.Unspecified, style.letterSpacing)
        }
    }

    /** F5: the bar's label text is not run through the kicker (uppercase) or the micro-label style. */
    @Test
    fun the_tab_bar_no_longer_uses_the_kicker_or_micro_label() {
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/TabBar.kt").readText()
        assertTrue("TabBar.kt uses Type.tabLabel", src.contains("Type.tabLabel("))
        assertTrue("TabBar.kt still uses Type.kicker", !src.contains("Type.kicker("))
        assertTrue("TabBar.kt still uses Type.microLabel", !src.contains("Type.microLabel("))
    }

    @Test
    fun only_vardr_labs_kickers_are_uppercase_and_tracked() {
        Appearance.setForTest(theme = AppTheme.VARDR)
        assertEquals("SPLIT", Type.kicker("Split"))
        assertEquals(0.22.em, Type.microLabel(false).letterSpacing)
        // The phone's own language, as iOS's textCase uses the environment's locale.
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            assertEquals("SPL\u0130T", Type.kicker("Split"))
        } finally {
            java.util.Locale.setDefault(before)
        }
        Appearance.setForTest(theme = AppTheme.NORSE)
        assertEquals("Split", Type.kicker("Split"))
        Appearance.setForTest(theme = AppTheme.AURORA)
        assertEquals("Split", Type.kicker("Split"))
    }

    @Test
    fun cards_and_buttons_take_the_themes_shape() {
        assertEquals(20, cardRadius(null, AppTheme.AURORA))
        assertEquals(24, cardRadius(24, AppTheme.AURORA))
        assertEquals(3, cardRadius(24, AppTheme.NORSE))
        assertEquals(0, cardRadius(null, AppTheme.VARDR))
        assertEquals(14, AppTheme.AURORA.buttonRadius)
        assertEquals(3, AppTheme.NORSE.buttonRadius)
        assertEquals(0, AppTheme.VARDR.buttonRadius)
    }

    /** Every section label's text goes through Type.kicker, so Vardr Labs'
     *  uppercase reaches all of them: for each `Type.microLabel(` use, the
     *  nearest `Text(` before it must contain `Type.kicker(` in between. */
    @Test
    fun every_section_label_goes_through_kicker() {
        val sources = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!
        for (f in sources) {
            if (f.name == "Tokens.kt") continue
            val src = f.readText()
            // D-213: ThemeArt.kt's SectionLabel composable uppercases through SectionLabels.annotated (ThemeArtTest
            // pins it); only its body is exempt, and it must call that.
            val exempt = if (f.name == "ThemeArt.kt") {
                val start = src.indexOf("fun SectionLabel(")
                assertTrue("ThemeArt.kt: SectionLabel", start >= 0)
                val end = src.indexOf("\n}\n", start)
                assertTrue("SectionLabel goes through SectionLabels.annotated", src.substring(start, end).contains("SectionLabels.annotated("))
                start until end
            } else {
                IntRange.EMPTY
            }
            var at = src.indexOf("Type.microLabel(")
            while (at >= 0) {
                if (at !in exempt) {
                    val text = src.lastIndexOf("Text(", at)
                    assertTrue("${f.name}: a section label without Type.kicker at $at",
                        text >= 0 && src.substring(text, at).contains("Type.kicker("))
                }
                at = src.indexOf("Type.microLabel(", at + 1)
            }
        }
    }

    // ---- D-215: IBM Plex Sans replaces the system face (the founder, 2026-10-15) ----

    /** The four bundled files, by weight: what `Type.sans` offers Compose, read off the family itself. */
    private fun sansFiles(): Map<Int, Int> {
        val family = Type.sans as androidx.compose.ui.text.font.FontListFontFamily
        return family.fonts.associate { f -> f.weight.weight to (f as androidx.compose.ui.text.font.ResourceFont).resId }
    }

    @Test
    fun sans_is_the_four_bundled_plex_sans_files() {
        assertEquals(
            mapOf(
                400 to R.font.ibmplexsans_regular, 500 to R.font.ibmplexsans_medium,
                600 to R.font.ibmplexsans_semibold, 700 to R.font.ibmplexsans_bold,
            ),
            sansFiles(),
        )
    }

    /** The system face's buckets, kept: 650 and up Bold, 550 SemiBold, 450 Medium, else Regular. The JVM cannot run
     *  Compose's own font matcher (it needs a Context), so the pin is the weight WE hand it: every request is bucketed
     *  here to exactly one of the four files' weights, so Compose never has to pick a nearest. */
    @Test
    fun sans_weights_fall_into_the_system_faces_buckets() {
        val files = sansFiles()
        val expected = listOf(
            100 to 400, 300 to 400, 400 to 400, 449 to 400, 450 to 500, 500 to 500, 549 to 500,
            550 to 600, 600 to 600, 649 to 600, 650 to 700, 700 to 700, 900 to 700,
        )
        for ((asked, bucket) in expected) {
            assertEquals("asked $asked", bucket, Type.sansWeight(asked).weight)
            assertTrue("a file exists for $bucket", bucket in files)
        }
        assertEquals(R.font.ibmplexsans_semibold, files.getValue(Type.sansWeight(600).weight))
        assertEquals(R.font.ibmplexsans_bold, files.getValue(Type.sansWeight(700).weight))
    }

    @Test
    fun the_sans_styles_carry_the_sans_family_and_the_bucketed_weight() {
        for (theme in listOf(AppTheme.AURORA, AppTheme.NORSE)) {
            Appearance.setForTest(theme = theme)
            assertEquals("$theme body", Type.sans, Type.body(false).fontFamily)
            assertEquals("$theme label", Type.sans, Type.label(false).fontFamily)
            assertEquals("$theme label weight", androidx.compose.ui.text.font.FontWeight(600), Type.label(false).fontWeight)
            assertEquals("$theme tab", Type.sans, Type.tabLabel(false).fontFamily)
        }
        Appearance.setForTest(theme = AppTheme.AURORA)
        assertEquals(Type.sans, Type.microLabel(false).fontFamily)
        Appearance.setForTest(theme = AppTheme.VARDR)
        assertTrue("Vardr Labs keeps Space Grotesk for body", Type.body(false).fontFamily !== Type.sans)
    }

    /** The twin of iOS's root `.font(TypeRole.body())`: Material's whole scale is drawn in the theme's BODY face, so an
     *  unstyled Text is Plex Sans in Aurora and Norse and Space Grotesk in Vardr Labs, on both phones. */
    @Test
    fun material_typography_draws_every_style_in_the_themes_body_face() {
        val d = androidx.compose.material3.Typography()
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            val bodyFamily = Type.body(false).fontFamily
            val t = Type.materialTypography(theme)
            val styles = listOf(
                t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall,
                t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall,
                t.labelLarge, t.labelMedium, t.labelSmall,
            )
            assertEquals(15, styles.size)
            for ((i, st) in styles.withIndex()) assertEquals("$theme style $i", bodyFamily, st.fontFamily)
            // D-215 fix wave (I1): iOS draws Plex Sans untracked, so Material's default tracking (0.1 to 0.5 sp) goes.
            for ((i, st) in styles.withIndex()) assertEquals("$theme style $i letter spacing", 0.sp, st.letterSpacing)
            assertEquals("$theme only the family and tracking change", d.bodyLarge.fontSize, t.bodyLarge.fontSize)
            assertEquals("$theme only the family and tracking change", d.titleMedium.fontWeight, t.titleMedium.fontWeight)
            assertEquals("$theme Material's line heights stay", d.bodyLarge.lineHeight, t.bodyLarge.lineHeight)
            // A FilterChip's label is Material's labelLarge (FilterChipTokens.LabelTextFont): the chips that remain
            // (App lock, Import, the currency choice, Add expense), in the body face at Medium.
            assertEquals("$theme labelLarge weight", androidx.compose.ui.text.font.FontWeight.Medium, t.labelLarge.fontWeight)
        }
        assertEquals(Type.sans, Type.materialTypography(AppTheme.AURORA).bodyLarge.fontFamily)
        assertEquals(Type.sans, Type.materialTypography(AppTheme.NORSE).bodyLarge.fontFamily)
        assertTrue(Type.sans != Type.materialTypography(AppTheme.VARDR).bodyLarge.fontFamily)
    }

    /** `weightFor`'s SANS branch buckets an off-file weight; with the branch gone the request would pass through. */
    @Test
    fun the_sans_branch_of_weight_for_buckets_off_file_weights() {
        Appearance.setForTest(theme = AppTheme.AURORA)
        assertEquals(Type.sans, Type.body(false).fontFamily)
        for ((asked, bucket) in listOf(300 to 400, 440 to 400, 460 to 500, 560 to 600, 640 to 600, 660 to 700)) {
            assertEquals("asked $asked", bucket, Type.weightFor(Role.BODY, asked).weight)
        }
        Appearance.setForTest(theme = AppTheme.VARDR)
        assertEquals("Space Grotesk keeps what is asked", 560, Type.weightFor(Role.BODY, 560).weight)
    }

    /** D-215: the leaks of the system face the source guard looks for, one pure function so it can be tried on
     *  samples as well as on the tree. Comments are stripped first. A site that draws something other than words
     *  (a rune, drawn with Vardr Runes by D-215's B5) is named in [allowed] with its reason; an entry that no
     *  longer matches fails, so the list cannot go stale. */
    private fun leaks(name: String, raw: String, allowed: Map<String, String> = emptyMap(), used: MutableMap<String, Int> = mutableMapOf()): List<String> {
        val src = raw.replace(Regex("/\\*[\\s\\S]*?\\*/")) { "\n".repeat(it.value.count { c -> c == '\n' }) }.replace(Regex("(?<![:\"])//.*"), "")
        val out = mutableListOf<String>()
        fun flag(re: Regex, what: String) {
            for (m in re.findAll(src)) out += "$name: $what at ${src.substring(0, m.range.first).count { it == '\n' } + 1}"
        }
        flag(Regex("\\bFontFamily\\s*\\.\\s*(Companion\\s*\\.\\s*)?(Default|SansSerif|Serif|Monospace|Cursive)\\b"), "FontFamily system face")
        flag(Regex("\\bTypeface\\s*\\.\\s*(DEFAULT_BOLD|DEFAULT|SANS_SERIF|SERIF|MONOSPACE)\\b"), "Typeface system face")
        flag(Regex("\\bTypeface\\s*\\.\\s*(create\\s*\\(|defaultFromStyle)"), "Typeface.create")
        flag(Regex("\\bTextStyle\\s*\\.\\s*Default\\b"), "TextStyle.Default")
        flag(Regex("fontFamily\\s*=\\s*null\\b"), "fontFamily = null")
        flag(Regex("import\\s+android\\.graphics\\.Paint\\b"), "native Paint import")
        flag(Regex("\\b(TextPaint|StaticLayout|drawTextOnPath|nativeCanvas)\\b"), "native text drawing")
        // D-215 fix wave (B2-m2): a device font by name, a family named by a string, and Material's scale built
        // anywhere but Tokens.kt (its default styles carry no family, so they draw the platform default).
        flag(Regex("\\bDeviceFontFamilyName\\s*\\("), "DeviceFontFamilyName")
        flag(Regex("\\bFontFamily\\s*\\(\\s*\""), "FontFamily(\" string family")
        if (name != "Tokens.kt") flag(Regex("(?<![A-Za-z0-9_])Typography\\s*\\("), "Typography( outside Tokens.kt")
        for (m in Regex("(?<![A-Za-z0-9_])TextStyle\\s*\\(").findAll(src)) {
            var depth = 0
            var i = m.range.last
            val start = i
            while (i < src.length) {
                if (src[i] == '(') depth++
                if (src[i] == ')') { depth--; if (depth == 0) break }
                i++
            }
            val args = src.substring(start, i)
            if (!Regex("\\bfontFamily\\s*=").containsMatchIn(args)) {
                val around = src.substring(maxOf(0, m.range.first - 60), i)
                // An entry is "File.kt|snippet": scoped to its file, and counted (one site each).
                val key = allowed.keys.firstOrNull { it.substringBefore('|') == name && around.contains(it.substringAfter('|')) }
                if (key != null) used[key] = (used[key] ?: 0) + 1 else out += "$name: TextStyle( without fontFamily at ${src.substring(0, m.range.first).count { it == '\n' } + 1}"
            }
        }
        for (m in Regex("android\\s*\\.\\s*graphics\\s*\\.\\s*Paint\\s*\\(").findAll(src)) {
            val key = "$name|android.graphics.Paint("
            if (key in allowed) used[key] = (used[key] ?: 0) + 1 else out += "$name: android.graphics.Paint( at ${src.substring(0, m.range.first).count { it == '\n' } + 1}"
        }
        return out
    }

    @Test
    fun the_leak_guard_flags_every_form_it_claims_to() {
        val bad = listOf(
            "val f = FontFamily.Default",
            "val f = androidx.compose.ui.text.font.FontFamily.SansSerif",
            "val f = FontFamily\n    .Serif",
            "val f = FontFamily.Monospace",
            "val f = FontFamily.Cursive",
            "val f = FontFamily.Companion.Default",
            "val t = Typeface.DEFAULT",
            "val t = android.graphics.Typeface.DEFAULT_BOLD",
            "val t = Typeface.SANS_SERIF",
            "val t = Typeface.SERIF",
            "val t = Typeface.MONOSPACE",
            "val t = Typeface.create(\"x\", Typeface.NORMAL)",
            "val t = Typeface\n    .create(\"x\", 0)",
            "val s = TextStyle(fontSize = 12.sp)",
            "val s = androidx.compose.ui.text.TextStyle(\n    fontSize = 12.sp,\n    color = c,\n)",
            "val s = TextStyle(fontSize = f(1, 2), color = x)",
            "val s = TextStyle(fontFamily = null)",
            "val s = TextStyle.Default",
            "val p = android.graphics.Paint()",
            "val p = TextPaint()",
            "import android.graphics.Paint\nval p = Paint()",
            "val f = FontFamily(Font(DeviceFontFamilyName(\"sans-serif\")))",
            "val f = FontFamily(\"sans-serif\")",
            "val t = Typography()",
            "MaterialTheme(typography = androidx.compose.material3.Typography(bodyLarge = s)) { }",
        )
        for (src in bad) assertTrue("flags: $src", leaks("sample.kt", src).isNotEmpty())
        val good = listOf(
            "val s = TextStyle(fontFamily = Type.sans, fontSize = 12.sp)",
            "val s = TextStyle(\n    fontSize = 12.sp,\n    fontFamily = family(Role.BODY),\n)",
            "Notification.BigTextStyle().bigText(body)",
            "// FontFamily.Default in a comment\nval x = 1",
            "/* Typeface.DEFAULT in a block comment */ val x = 1",
            "val s = Type.body(dark).copy(fontSize = 14.sp)",
            "val t = Type.materialTypography(theme)",
            "val f = FontFamily(Font(R.font.ibmplexsans_regular))",
        )
        for (src in good) assertEquals("passes: $src", emptyList<String>(), leaks("sample.kt", src))
        // Material's scale is built in Tokens.kt, and only there.
        assertEquals(emptyList<String>(), leaks("Tokens.kt", "val d = Typography()"))
    }

    /** D-215: every `MaterialTheme(` passes a typography. A pure function, so a call with no `{` after it (a
     *  regression, or a sample) is reported as a failure, never a crash (fix wave, m6). */
    private fun materialThemeLeaks(name: String, text: String): List<String> {
        val out = mutableListOf<String>()
        var at = text.indexOf("MaterialTheme(")
        while (at >= 0) {
            val close = text.indexOf("{", at)
            if (close < 0) {
                out += "$name: MaterialTheme( with no { after it at $at"
                break
            }
            if (!text.substring(at, close).contains("typography =")) out += "$name: MaterialTheme( without typography at $at"
            at = text.indexOf("MaterialTheme(", at + 1)
        }
        return out
    }

    @Test
    fun the_material_theme_guard_fails_and_never_crashes() {
        assertTrue(materialThemeLeaks("s.kt", "MaterialTheme(colorScheme = c) {").isNotEmpty())
        assertTrue("no brace after it", materialThemeLeaks("s.kt", "MaterialTheme(colorScheme = c, content = body)").isNotEmpty())
        assertTrue(materialThemeLeaks("s.kt", "MaterialTheme(typography = t) { }\nMaterialTheme(colorScheme = c) { }").isNotEmpty())
        assertEquals(emptyList<String>(), materialThemeLeaks("s.kt", "MaterialTheme(colorScheme = c, typography = t) {"))
        assertEquals(emptyList<String>(), materialThemeLeaks("s.kt", "val x = MaterialTheme.colorScheme"))
    }

    /** D-215 fix wave (m2): the "@" before the username field is in the row's own role on both phones, the body
     *  face the field's text uses (Android's `textStyle = Type.body(dark)`, iOS's root `.font(TypeRole.body())`). */
    @Test
    fun the_username_at_sign_is_in_the_body_role_on_both_shells() {
        val kt = java.io.File("src/main/java/com/vardrlabs/skipti/People.kt").readText()
        assertTrue("Android", "prefix = { Text(\"@\", style = Type.body(dark).copy(color = Tok.accent(dark))) }" in kt)
        val swift = java.io.File("../../ios/Skipti/App/People.swift").readText()
        assertTrue("iOS", "Text(verbatim: \"@\").font(TypeRole.body()).foregroundStyle(Tokens.accent)" in swift)
    }

    /** D-215 fix (F2): the Appearance page's detail lines (D-222: "Norse is always dark." under the mode control, in
     *  Norse only, and the text-size caption) are 14 on both phones: Android's `AppearanceCopy.DETAIL_SIZE` (sp) is read against iOS's `AppearanceCopy.detailSize`
     *  (pt) in the Swift source, and both lines draw it (Type.bodyDim is 16 sp, which this page no longer uses). */
    @Test
    fun the_appearance_detail_lines_are_the_same_size_on_both_shells() {
        val kt = java.io.File("src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt").readText()
        val androidSize = Regex("""const val DETAIL_SIZE = (\d+)""").find(kt)!!.groupValues[1].toInt()
        val swift = java.io.File("../../ios/Skipti/App/AccountHubScreen.swift").readText()
        val iosSize = Regex("""static let detailSize: CGFloat = (\d+)""").find(swift)!!.groupValues[1].toInt()
        assertEquals(iosSize, androidSize)
        assertEquals(14, androidSize)
        assertEquals("both Appearance lines", 2, "Type.bodyDim(dark).copy(fontSize = AppearanceCopy.DETAIL_SIZE.sp)".let { kt.split(it).size - 1 })
    }

    /** D-215 (the founder, 2026-10-15): no visible text is drawn in the phone's system face. Roboto reaches the
     *  screen three ways: a named system family, a `TextStyle(` built with no `fontFamily` (it drops to the
     *  platform default, and so does `BasicText` with one), and a native Paint or Typeface. Material's own scale is
     *  covered by `Type.materialTypography(Appearance.theme)`, remembered keyed on the theme (read in composition, so a
     *  theme switch rebuilds it), and every Material theme must pass a typography. */
    @Test
    fun no_visible_text_uses_the_system_face() {
        // D-215 B5: every rune is drawn through `Type.rune(` (Vardr Runes) and the Paint proof is retired. The mechanism
        // stays (file-scoped, counted). D-246 names the one site: GlyphGlow's raster fills a rune's OUTLINE
        // (`Paint.getTextPath`, in the Typeface Compose resolves for `Type.runes`) into the glow's mask, once a process;
        // it draws no text, so no system face can reach the screen through it.
        val allowed = mapOf(
            "ThemeMotion.kt|android.graphics.Paint(" to "D-246: the glyph glow's mask, a rune's outline in Vardr Runes, never text",
        )
        val used = mutableMapOf<String, Int>()
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val sources = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("sources found", sources.size > 40)
        val found = sources.flatMap { leaks(it.name, it.readText(), allowed, used) }
        assertEquals(emptyList<String>(), found)
        assertEquals("every allowlist entry matches exactly one site", allowed.keys.associateWith { 1 }, used)

        val root = java.io.File(dir, "AppRoot.kt").readText()
        // Remembered per theme (fix wave, B2-m6): built once per theme, rebuilt on a switch.
        assertTrue(
            "AppRoot passes Material's typography",
            root.contains("typography = remember(Appearance.theme) { Type.materialTypography(Appearance.theme) }"),
        )
        assertEquals(emptyList<String>(), sources.flatMap { materialThemeLeaks(it.name, it.readText()) })
    }

    // ---- D-217: one type scale, iPhone's way ----

    /** D-217: iOS's `TypeScale` (Swift source) is the table; Android's `TypeScale` has the same names and values, in
     *  the same order. A missing or extra name on either side fails. */
    @Test
    fun the_type_scale_is_the_ios_table() {
        val swift = java.io.File("../../ios/Skipti/App/TypeSystem.swift").readText()
        // D-217 final fix: anchored, the enum at a line start (exactly one) and each constant a whole line of its body.
        val opens = Regex("""(?m)^enum TypeScale \{$""").findAll(swift).toList()
        assertEquals("TypeSystem.swift has one enum TypeScale", 1, opens.size)
        val start = opens.single().range.first
        val end = swift.indexOf("\n}\n", start)
        assertTrue("enum TypeScale closes", end > start)
        val ios = Regex("""(?m)^    static let (\w+): CGFloat = ([0-9.]+)$""").findAll(swift.substring(start, end))
            .map { it.groupValues[1] to it.groupValues[2].toFloat() }.toList()
        assertTrue("TypeScale constants found", ios.size >= 12)
        assertEquals("names and values, in order", ios, TypeScale.all)
        assertEquals(
            listOf(
                "largeTitle" to 34f, "inlineTitle" to 17f, "dateLine" to 14f, "heroAmount" to 44f, "heroLine" to 15f,
                "settledLine" to 13f, "groupCount" to 13f, "detail" to 14f, "statValue" to 16f, "settleIntro" to 15f,
                "shareInput" to 15f, "avatarInitial" to 0.38f,
                // D-221 (art.py sections 21 and 22): the display names, the settled percent, the seal's number, the pill.
                "displayName" to 24f, "settledPercent" to 28f, "sealNumber" to 15f, "pill" to 13f,
                // D-222 (art.py sections 26 and 27): the mode control's words, the sample line, the slider's two A's.
                "segment" to 14f, "sample" to 19f, "rangeSmall" to 14f, "rangeLarge" to 23f,
                // D-223 (art.py sections 30, 32 and 34): a ledger card's name, a Lately line, the empty state's title.
                "ledgerName" to 16f, "feedLine" to 15f, "emptyTitle" to 20f,
                // D-224 (art.py section 38): the toast's words, the Look Book's 600 12 x 1.28.
                "toast" to 15f,
                // D-225 (art.py sections 41 to 44): About's prose, a licence's text, the lock screen's title, the wordmark.
                "prose" to 15f, "footnote" to 13f, "lockTitle" to 28f, "wordmark" to 32f,
                // D-245: the welcome's wordmark (W1), iOS's `welcomeMark`.
                "welcomeMark" to 52f,
            ),
            TypeScale.all,
        )
        assertEquals(34f, TypeScale.largeTitle, 0f)
    }

    /** D-217: the `name` role is the theme's BODY family at weight 600 (Plex Sans SemiBold, or Space Grotesk at 600). */
    @Test
    fun the_name_role_is_the_body_face_at_600() {
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            val style = Type.name(false)
            assertEquals("$theme family", Type.body(false).fontFamily, style.fontFamily)
            assertEquals("$theme weight", Type.weightFor(Role.BODY, 600), style.fontWeight)
            assertEquals("$theme weight is 600", 600, style.fontWeight!!.weight)
            assertEquals("$theme size", 16.0f, style.fontSize.value, 0.001f)
            assertEquals("$theme no fixed line height", androidx.compose.ui.unit.TextUnit.Unspecified, style.lineHeight)
            assertEquals("$theme colour", Tok.text(false), style.color)
            assertEquals("$theme size arg", 19.0f, Type.name(true, 19).fontSize.value, 0.001f)
        }
    }
    // ---- D-217 Task 3: the call sites read the table and the roles ----

    private fun main(file: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$file").readText()

    /** Each (file, anchor, count) must hold exactly; a red names every file, anchor and count found. */
    private fun assertSites(sites: List<Triple<String, String, Int>>, what: String) {
        val bad = sites.mapNotNull { (file, anchor, count) ->
            val found = main(file).split(anchor).size - 1
            if (found == count) null else "$what: $file has $found of $count at: $anchor"
        }
        assertEquals(emptyList<String>(), bad)
    }

    private fun assertAbsent(sites: List<Pair<String, String>>, what: String) {
        val bad = sites.filter { (file, banned) -> banned in main(file) }.map { (file, banned) -> "$what: $file still has: $banned" }
        assertEquals(emptyList<String>(), bad)
    }

    /** D-217: every family's call sites take their size from `TypeScale`, not a literal (iOS's values are the scale;
     *  the twin of iOS's testTheSizeFamiliesComeFromTheTable). The titles are the bar's (SkiptiBarTest). */
    @Test
    fun the_size_families_come_from_the_table() {
        assertSites(
            listOf(
                Triple("OverviewTab.kt", "Type.numericColored(TypeScale.heroAmount, Tok.onHero(dark), androidx.compose.ui.text.font.FontWeight.Bold)", 1),
                // D-223: + the hero's who-owes line. The By group card's "Settled" (settledLine) went with it; a ledger
                // card's line is at the detail size (HomeParts.kt).
                Triple("OverviewTab.kt", "fontSize = TypeScale.heroLine.sp", 3),
                Triple("OverviewTab.kt", "fontSize = TypeScale.settledLine.sp", 0),
                // D-223: the ledger card's line, Lately's "See all" and a row's meta line.
                Triple("HomeParts.kt", "fontSize = TypeScale.detail.sp", 3),
                Triple("HomeParts.kt", "Type.numericColored(TypeScale.detail, colour, FontWeight.SemiBold)", 1),
                Triple("OverviewTab.kt", "Type.numericColored(TypeScale.statValue, colour)", 1),
                Triple("OverviewTab.kt", "TypeScale.detail.sp", 3), // decision 122: the could-not-sync line
                Triple("GroupsTab.kt", "TypeScale.detail.sp", 4),
                Triple("ExpenseListScreen.kt", "fontSize = TypeScale.groupCount.sp", 1),
                Triple("ExpenseDetailScreen.kt", "fontSize = TypeScale.detail.sp", 2),
                Triple("SettleAll.kt", "fontSize = TypeScale.settleIntro.sp", 2),
                // D-221: + the next card's why line. D-222: + You's row summaries.
                // D-224: the Haptics row's summary is the tenth. D-225: You's rows and the Haptics row draw through the shared
                // ListRow (its summary and the card caption are ListRows.kt's two).
                // D-225 T5: 5, Security's erase card, Your data's export and import lines are list rows (ListRows.kt).
                Triple("AccountHubScreen.kt", "fontSize = TypeScale.detail.sp", 5),
                Triple("ListRows.kt", "fontSize = TypeScale.detail.sp", 2),
                // D-221: You's name, the progress pill.
                Triple("AccountHubScreen.kt", "fontSize = TypeScale.displayName.sp", 1),
                Triple("YouProgress.kt", "fontSize = TypeScale.pill.sp", 1),
                Triple("Profile.kt", "AvatarStyle.initialStyle(dark, size.value)", 1),
                Triple("ThemeArt.kt", "(size * TypeScale.avatarInitial).sp", 1),
                // D-225 T7 (Design 11): the share fields are field rows, styled once in the split card's rows and once
                // after the card (Itemised's line, Tax and Tip).
                Triple("AddExpenseScreen.kt", "Type.numeric(TypeScale.shareInput, dark = dark)", 2),
                // D-217 look fix 2 (look D17): the lines the sweep missed, which iOS draws at 13 or 14, not the default 16.
                Triple("ActivityFeed.kt", "fontSize = TypeScale.groupCount.sp", 2),
                // D-225 T7 (Design 12): New group's three captions and Your name's are card captions (ListRows.kt), with
                // People's two (T5).
                Triple("GroupScreens.kt", "Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)", 0),
                Triple("GroupScreens.kt", "CardCaption(", 6),
                Triple("Profile.kt", "in your groups.\", style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)", 1),
                // D-225 T5: AppLock.kt 1 (the rooted warning; the privacy detail is the row's summary), CurrencyChoice.kt 1
                // (the old Currency settings went); the lock title, About's prose, wordmark and licence text from the scale.
                Triple("AppLock.kt", "fontSize = TypeScale.detail.sp", 1),
                Triple("CurrencyChoice.kt", "Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)", 1),
                Triple("AppLock.kt", "fontSize = TypeScale.lockTitle.sp", 1),
                Triple("About.kt", "fontSize = TypeScale.prose.sp", 7),
                Triple("About.kt", "fontSize = TypeScale.wordmark.sp", 1),
                Triple("About.kt", "fontSize = TypeScale.footnote.sp", 1),
                // D-217 look fix 3: Import's 14 lines (intro, a source's detail, the people count, the currency note, the
                // "which is you" note, the review's applying note), the "You" marker, the error line, and the sign-in
                // confirmation's detail, the erase failure and the other-account paragraph.
                Triple("ImportFlow.kt", "Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)", 6),
                Triple("ImportFlow.kt", "tag.copy(fontSize = TypeScale.detail.sp)", 1),
                Triple("ImportFlow.kt", "ErrorLine(it, dark, detail = true)", 1),
                Triple("GroupScreens.kt", "base.copy(fontSize = TypeScale.detail.sp)", 1),
                // D-224 T6: + the erased screen's words (EraseCopy.DONE_BODY), laid out as the signed-in screen's.
                // D-225 T5: + the Erase page's detail (EraseCopy.DETAIL, moved from Security's card).
                Triple("AccountOwner.kt", "Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)", 4),
                Triple("AccountOwner.kt", "ErrorLine(EraseCopy.FAILED, dark, detail = true)", 1),
            ),
            "a size family",
        )
        assertAbsent(
            listOf(
                "OverviewTab.kt" to "numericColored(40,",
                "OverviewTab.kt" to "color = Color.White, fontSize = 14.sp",
                "AddExpenseScreen.kt" to "Type.numeric(16, dark = dark)",
                "Profile.kt" to "size.value * 0.36f",
                "ThemeArt.kt" to "fontWeight = FontWeight(600), color = color)",
                "ThemeArt.kt" to "Type.label(dark, (size * 0.42f).toInt())",
                "ThemeArt.kt" to "(size * 0.36f).sp", // D-220: the diamond's smaller letter is gone
                "ActivityFeed.kt" to "filter { it.isNotEmpty() }), style = Type.bodyDim(dark))",
                "ActivityFeed.kt" to "filter { it.isNotEmpty() }),\n                style = Type.bodyDim(dark),",
                "Profile.kt" to "in your groups.\", style = Type.bodyDim(dark))",
                "AppLock.kt" to "Text(PrivacyCopy.CAPTURE_DETAIL, style = Type.bodyDim(dark))",
                "CurrencyChoice.kt" to "Text(name, style = Type.bodyDim(dark), modifier",
                "CurrencyChoice.kt" to "Text(CurrencyCopy.DETAIL, style = Type.bodyDim(dark))",
                "ImportFlow.kt" to "Text(ImportCopy.INTRO, style = Type.bodyDim(dark))",
                "ImportFlow.kt" to "Text(option.detail, style = Type.bodyDim(dark))",
                "ImportFlow.kt" to "Text(ImportCopy.SETTLES_DETAIL, style = Type.bodyDim(dark))",
                "ImportFlow.kt" to "Text(ImportCopy.WHICH_IS_YOU, style = Type.bodyDim(dark))",
                "ImportFlow.kt" to "Text(\"Applying writes all selected expenses at once.\", style = Type.bodyDim(dark))",
                "AccountOwner.kt" to "Text(SignedInCopy.DETAIL, style = Type.bodyDim(dark), textAlign",
                "AccountOwner.kt" to "Text(OtherAccountCopy.BODY, style = Type.bodyDim(dark))",
            ),
            "a literal size",
        )
    }

    /** D-217 look fix 2: the sizes those sites take are iOS's own at the twin line (13 for an activity row's meta line,
     *  14 for a description under a field, a hint, the security paragraph and a currency's name), read from the Swift. */
    @Test
    fun the_missed_detail_lines_take_ios_sizes() {
        assertEquals(13f, TypeScale.groupCount, 0f)
        assertEquals(14f, TypeScale.detail, 0f)
        fun ios(name: String) = java.io.File("../../ios/Skipti/App/$name").readText()
        val activity = ios("ActivityFeed.swift")
        assertEquals(2, activity.split(".font(TypeRole.body(13))").size - 1)
        // D-225 T6: New group's three lines are card captions now, at TypeScale.detail (14) through ListRows.swift's
        // CardCaption (read below).
        val sheets = ios("GroupSheets.swift")
        for (line in listOf(
            "CardCaption(\"A group settles in one currency, and it cannot be changed later.\")",
            "CardCaption(\"Add people by name. They do not need SKIPTI; linking a name to their phone comes later.\")",
            "CardCaption(\"You see yourself as \\\"Name (You)\\\". Others in the group see only the name.\")",
        )) assertTrue(line, sheets.contains(line))
        // D-245 (U17): iOS's Profile caption names the scale's detail (14) instead of the literal (stage 1's change).
        assertTrue(ios("Profile.swift").contains("Text(\"Shown as “\\(shown) (You)” in your groups.\")\n                            .font(TypeRole.body(TypeScale.detail))"))
        // D-225 T4: the privacy detail is the privacy row's summary and the currency detail a card caption, both at
        // TypeScale.detail (14, above) through ListRows.swift; the jailbreak warning is its own card at the same size.
        val lock = ios("AppLock.swift")
        assertTrue(lock.contains("ListRow(title: PrivacyCopy.captureToggle, parts: [PrivacyCopy.captureDetail], summaryLines: 0,"))
        assertTrue(lock.contains("Text(verbatim: PrivacyCopy.modified(\"jailbroken\"))\n            .font(TypeRole.body(TypeScale.detail))"))
        val rows = ios("ListRows.swift")
        assertTrue(rows.contains("Separator.text(parts, size: TypeScale.detail)\n                        .font(TypeRole.body(TypeScale.detail))"))
        assertTrue(rows.contains("Text(verbatim: text)\n            .font(TypeRole.body(TypeScale.detail))"))
        val currency = ios("CurrencyChoice.swift")
        assertTrue(currency.contains("Text(verbatim: name)\n                        .font(TypeRole.body(14))"))
        assertTrue(ios("AccountHubScreen.swift").contains("CardCaption(CurrencyCopy.detail)"))
        // Import's setup page: seven lines at 14 (the intro, a source's detail, the people count, the currency note, the
        // "You" marker, the "which is you" note, the error), and the review's applying note.
        val flow = ios("ImportFlow.swift")
        assertEquals(7, flow.split("TypeRole.body(14)").size - 1)
        assertTrue(flow.contains("Text(verbatim: ImportCopy.intro)\n                    .font(TypeRole.body(14))"))
        assertTrue(flow.contains("Text(verbatim: option.detail).font(TypeRole.body(14))"))
        assertTrue(flow.contains("Text(verbatim: \"You\").font(TypeRole.body(14))"))
        assertTrue(flow.contains("Text(verbatim: error).font(TypeRole.body(14))"))
        assertTrue(ios("ImportReview.swift").contains("Text(\"Applying writes all selected expenses at once.\")\n                        .font(TypeRole.body(14))"))
        // The sign-in confirmation's detail, the erase failure and the other-account paragraph.
        val owner = ios("AccountOwner.swift")
        assertTrue(owner.contains("Text(verbatim: SignedInCopy.detail)\n                .font(TypeRole.body(14))"))
        assertTrue(owner.contains("Text(verbatim: EraseCopy.failed).font(TypeRole.body(14))"))
        assertTrue(owner.contains("Text(verbatim: OtherAccountCopy.body).font(TypeRole.body(14))"))
    }

    /** D-217 final fix (I2): money that does not fit shrinks, as iOS's `.minimumScaleFactor(0.6).lineLimit(1)` on the
     *  hero amount and the stat tiles (OverviewScreen.swift), continuously to 60% of its size and never cut: one line,
     *  no wrap, an auto size from the full size down to [AMOUNT_MIN_SCALE] of it. */
    @Test
    fun money_shrinks_to_fit_as_ios_does() {
        val ios = java.io.File("../../ios/Skipti/App/OverviewScreen.swift").readText()
        assertTrue(ios.contains("TypeRole.numeric(TypeScale.heroAmount, weight: 700, relativeTo: .largeTitle)).foregroundStyle(Tokens.onHero)\n                        .minimumScaleFactor(0.6).lineLimit(1)"))
        assertTrue(ios.contains("TypeRole.numeric(TypeScale.statValue, weight: 600)).foregroundStyle(colour).lineLimit(1).minimumScaleFactor(0.6)"))
        assertEquals(0.6f, AMOUNT_MIN_SCALE, 0f)
        assertEquals(26.4f, amountMinSize(TypeScale.heroAmount), 0.0001f)
        assertEquals(9.6f, amountMinSize(TypeScale.statValue), 0.0001f)
        val o = main("OverviewTab.kt")
        for (size in listOf("TypeScale.heroAmount", "TypeScale.statValue")) {
            assertEquals("$size shrinks", 1, o.split("autoSize = amountAutoSize($size),").size - 1)
        }
        assertTrue(o.contains("TextAutoSize.StepBased(minFontSize = amountMinSize(size).sp, maxFontSize = size.sp, stepSize = 0.2.sp)"))
        // Both stay on one line and never wrap; the old one-step shrink at nine characters is gone.
        assertEquals(2, Regex("""autoSize = amountAutoSize\(TypeScale\.\w+\),\s*maxLines = 1,\s*softWrap = false,""").findAll(o).count())
        // Past the 60% floor iOS truncates the tail (lineLimit 1): an ellipsis, never digits silently cut.
        assertEquals(2, Regex("""softWrap = false,\s*(//[^\n]*\s*)?overflow = TextOverflow\.Ellipsis,""").findAll(o).count())
        assertFalse(o.contains("value.length > 9"))
    }

    /** D-217: a name (a person, a group, an expense's title, a theme card) is drawn through the `name` role, the body
     *  face at 600, never `label` (Plex Mono in Vardr Labs, which serves button words). */
    @Test
    fun names_use_the_name_role() {
        assertSites(
            listOf(
                Triple("OverviewTab.kt", "Type.name(dark, 16)", 1),
                // D-223: the By group card's name went with it (L55); the ledger card, a Lately line and the two empty
                // titles take their TypeScale sizes.
                Triple("OverviewTab.kt", "Type.name(dark, 15)", 1),
                Triple("HomeParts.kt", "Type.name(dark, TypeScale.ledgerName.toInt())", 1),
                Triple("HomeParts.kt", "Type.name(dark, TypeScale.feedLine.toInt())", 1),
                Triple("HomeParts.kt", "Type.name(dark, TypeScale.emptyTitle.toInt())", 1),
                Triple("OverviewTab.kt", "Type.name(dark, TypeScale.emptyTitle.toInt())", 1),
                Triple("GroupsTab.kt", "Type.name(dark, 17)", 2),
                Triple("ExpenseListScreen.kt", "Type.name(dark, 17)", 1),
                Triple("ExpenseListScreen.kt", "Type.name(dark, 15)", 1),
                Triple("SettleAll.kt", "Type.name(dark, 17)", 1),
                Triple("AccountHubScreen.kt", "Type.name(dark, 14)", 1),
            ),
            "a name",
        )
        assertAbsent(
            listOf(
                "OverviewTab.kt" to "Text(p.name, style = Type.label(",
                "OverviewTab.kt" to "Text(named.group.name, style = Type.label(",
                "GroupsTab.kt" to "named.group.name, style = Type.label(",
                "GroupsTab.kt" to "Text(group.name, style = Type.label(",
                "ExpenseListScreen.kt" to "ExpenseCopy.PERSONAL, style = Type.label(",
                "ExpenseListScreen.kt" to "title.withRunes(), style = Type.label(",
                "SettleAll.kt" to "Text(named.group.name, style = Type.label(",
                "AccountHubScreen.kt" to "style = Type.label(dark, 14),\n",
            ),
            "a name drawn with label",
        )
    }

    /** D-217: the date line and the hero's first line are kickers (the micro role) at the date line's size; the hero's is
     *  steel (the accent) in Vardr Labs. */
    @Test
    fun the_date_line_and_hero_kicker_are_kickers() {
        assertSites(
            listOf(
                Triple("Profile.kt", "Type.kicker(date), style = Type.microLabel(dark).copy(fontSize = TypeScale.dateLine.sp)", 1),
                Triple("OverviewTab.kt", "Type.kicker(OverviewCopy.ACROSS)", 1),
                Triple("OverviewTab.kt", "Type.microLabel(dark).copy(fontSize = TypeScale.dateLine.sp, color = heroKickerColour)", 1),
                Triple("OverviewTab.kt", "if (HeroKicker.usesSteel(Appearance.theme)) Tok.accent(dark) else dim", 1),
            ),
            "a kicker",
        )
        assertEquals(listOf(false, false, true), AppTheme.entries.map(HeroKicker::usesSteel))
    }

    /** D-217: the five headings name what follows, so they take the section label (ᛟ before them in Norse), in the
     *  page's text colour as on iOS. */
    @Test
    fun the_five_section_headings_are_section_labels() {
        assertSites(
            listOf(
                Triple("OverviewTab.kt", "SectionLabel(OverviewCopy.PEOPLE, dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))", 1),
                // D-223: "By group" is gone (L55); "Your ledgers" takes its place, and Lately's label is one inside its card.
                Triple("OverviewTab.kt", "SectionLabel(OverviewCopy.BY_GROUP, dark, color = Tok.text(dark))", 0),
                Triple("OverviewTab.kt", "SectionLabel(LedgerCopy.TITLE, dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))", 1),
                Triple("HomeParts.kt", "SectionLabel(LatelyCopy.TITLE, dark, color = Tok.text(dark))", 1),
                Triple("OverviewTab.kt", "SectionLabel(OverviewCopy.WHO_OWES, dark, color = Tok.text(dark))", 1),
                Triple("OverviewTab.kt", "SectionLabel(OverviewCopy.RECENT, dark, color = Tok.text(dark))", 1),
                Triple("OverviewTab.kt", "SectionLabel(OverviewCopy.WHERE_IT_WENT, dark, color = Tok.text(dark))", 1),
            ),
            "a section heading",
        )
        assertAbsent(
            listOf(
                "OverviewTab.kt" to "Text(OverviewCopy.PEOPLE,",
                "OverviewTab.kt" to "Text(OverviewCopy.BY_GROUP,",
                "OverviewTab.kt" to "Text(OverviewCopy.WHO_OWES,",
                "OverviewTab.kt" to "Text(OverviewCopy.RECENT,",
                "OverviewTab.kt" to "Text(OverviewCopy.WHERE_IT_WENT,",
            ),
            "a heading still drawn as label text",
        )
    }

    /** D-217: no text style in `Type` fixes a line height (iOS draws the face's natural height); `Type.body`'s 22 sp,
     *  copied into every 12, 13 and 14 sp line, is gone. */
    @Test
    fun no_fixed_line_height_in_type() {
        val tokens = main("Tokens.kt")
        val type = tokens.substring(tokens.indexOf("object Type {"))
        assertEquals("a `lineHeight =` inside object Type", 0, Regex("""lineHeight\s*=""").findAll(type).count())
        // D-217 final fix: nor on any copy of a style anywhere (iOS draws every face at its natural height).
        val files = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scanned ${files.size} files", files.size > 40)
        val fixed = files.filter { Regex("""lineHeight\s*=""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a fixed lineHeight in", emptyList<String>(), fixed)
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            val styles = mapOf(
                "body" to Type.body(false), "bodyDim" to Type.bodyDim(true), "name" to Type.name(false), "label" to Type.label(false),
                "display" to Type.display(false), "title" to Type.title(false, TypeScale.inlineTitle), "microLabel" to Type.microLabel(false),
                "tabLabel" to Type.tabLabel(false), "numeric" to Type.numeric(16, dark = false),
                "numericColored" to Type.numericColored(16, androidx.compose.ui.graphics.Color.Red),
            )
            for ((name, style) in styles) {
                assertEquals("$theme $name", androidx.compose.ui.unit.TextUnit.Unspecified, style.lineHeight)
            }
        }
    }

    /** D-217: a row title that stops at a line limit ends in an ellipsis (iOS's do), not a clip: every `Text(` that
     *  draws a name with `maxLines` carries `TextOverflow.Ellipsis`, and the three listed row titles are among them. */
    @Test
    fun row_titles_end_in_an_ellipsis() {
        assertSites(
            listOf(
                // D-223: the By group card's went with it; the ledger card's name is one line with an ellipsis.
                Triple("OverviewTab.kt", "Type.name(dark, 15), maxLines = 1, overflow = TextOverflow.Ellipsis", 1),
                Triple("HomeParts.kt", "Type.name(dark, TypeScale.ledgerName.toInt()),\n                maxLines = 1, overflow = TextOverflow.Ellipsis", 1),
                Triple("ExpenseListScreen.kt", "Type.name(dark, 15), maxLines = 2, overflow = TextOverflow.Ellipsis", 1),
            ),
            "a row title",
        )
        val bad = java.io.File("src/main/java/com/vardrlabs/skipti").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.flatMap { f ->
            val text = f.readText()
            Regex("""Text\(""").findAll(text).mapNotNull { m ->
                var depth = 0
                var i = m.range.last
                while (i < text.length) {
                    if (text[i] == '(') depth++
                    if (text[i] == ')') { depth--; if (depth == 0) break }
                    i++
                }
                val call = text.substring(m.range.first, minOf(i + 1, text.length))
                if ("Type.name(" in call && "maxLines" in call && "TextOverflow.Ellipsis" !in call) "${f.name}: ${call.take(90)}" else null
            }
        }.toList()
        assertEquals(emptyList<String>(), bad)
    }
    /** D-217: the Look Book's weights: the greeting 700 with Norse tracked +.02 em; You's name 700 and handle 500. */
    @Test
    fun the_look_books_weights_are_set() {
        assertEquals(22f, GreetingStyle.SIZE, 0f)
        assertEquals(listOf(androidx.compose.ui.unit.TextUnit.Unspecified, 0.02.em, androidx.compose.ui.unit.TextUnit.Unspecified), AppTheme.entries.map(GreetingStyle::tracking))
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            val greeting = GreetingStyle.style(false)
            assertEquals("$theme greeting weight", 700, greeting.fontWeight!!.weight)
            assertEquals("$theme greeting size", 22f, greeting.fontSize.value, 0.001f)
            assertEquals("$theme greeting tracking", GreetingStyle.tracking(theme), greeting.letterSpacing)
            assertEquals("$theme greeting face", Type.display(false).fontFamily, greeting.fontFamily)
            val name = YouStyle.name(false)
            assertEquals("$theme name weight", 700, name.fontWeight!!.weight)
            assertEquals("$theme name size", 24f, name.fontSize.value, 0.001f)
            assertEquals("$theme name face", Type.display(false).fontFamily, name.fontFamily)
            val handle = YouStyle.handle(false)
            assertEquals("$theme handle weight", 500, handle.fontWeight!!.weight)
            assertEquals("$theme handle size", 15f, handle.fontSize.value, 0.001f)
            assertEquals("$theme handle face", Type.body(false).fontFamily, handle.fontFamily)
            assertEquals("$theme handle colour", Tok.textDim(false), handle.color)
        }
        assertSites(
            listOf(
                Triple("Profile.kt", "style = GreetingStyle.style(dark)", 1),
                Triple("AccountHubScreen.kt", "style = YouStyle.name(dark)", 1),
                Triple("AccountHubScreen.kt", "style = YouStyle.handle(dark)", 1),
            ),
            "a weight",
        )
    }

    /** D-217: the avatar's letter is 0.38 of the avatar (both faces), not 0.36. */
    @Test
    fun the_avatar_initial_is_thirty_eight_percent_of_the_avatar() {
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            assertEquals("$theme 40", 15.2f, AvatarStyle.initialStyle(false, 40f).fontSize.value, 0.001f)
            assertEquals("$theme 104", 39.52f, AvatarStyle.initialStyle(true, 104f).fontSize.value, 0.001f)
        }
        // D-221 (K05, K06): the display face at 700 in every theme (Norse's Cormorant SC, the others' display face).
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            assertEquals("$theme face", Type.display(false).fontFamily, AvatarStyle.initialStyle(false, 40f).fontFamily)
            assertEquals("$theme weight", 700, AvatarStyle.initialStyle(false, 40f).fontWeight!!.weight)
        }
    }

    /** D-220: People's recents row shows its "Added" / "On SKIPTI" chip as iOS does: a pill (selected when added,
     *  the accent otherwise) in the label face at 11, padded 8 by 3. */
    @Test
    fun people_shows_the_added_chip() {
        val people = main("People.kt")
        assertTrue("the tone", "val tone = if (isAdded) PillTone.SELECTED else PillTone.ACCENT" in people)
        assertTrue("the words", "style = Type.label(dark, 11).copy(color = ThemePills.text(tone, dark))" in people)
        // D-224 T6: the screen sits in a Box with its own toast host now, so the chain is matched across any indent.
        assertTrue("the pill and its padding", Regex("""\.themePill\(tone, dark\)\s*\.padding\(horizontal = 8\.dp, vertical = 3\.dp\)""").containsMatchIn(people))
        assertFalse("no plain 11 sp words", "fontSize = 11.sp" in people)
        val ios = java.io.File("../../ios/Skipti/App/People.swift").readText()
        assertTrue("iOS's chip face", ".font(TypeRole.label(11))" in ios)
        assertTrue("iOS's chip padding", ".padding(.horizontal, 8).padding(.vertical, 3)" in ios)
    }
}
