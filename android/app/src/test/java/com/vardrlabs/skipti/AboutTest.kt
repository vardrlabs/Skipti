package com.vardrlabs.skipti

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-146: About and the licence inventory, the twin of iOS AboutTests. In
 * the shared test set: both flavours run it. The inventory is read from
 * the file the APK's assets are built from (native/licences/).
 */
class AboutTest {

    private fun inventory(): LicenceInventory {
        val file = File("../../native/licences/licences.json")
        assertTrue("the inventory exists at " + file.absolutePath, file.isFile)
        return Licences.parse(file.readText())
    }

    /** The play build shows what it ships (D-191 retired the direct flavour):
     *  Google's and Firebase's libraries, the Android core's, and nothing of
     *  the iPhone's Swift packages. */
    @Test
    fun the_play_build_shows_only_what_it_ships() {
        val inv = inventory()
        assertEquals(emptyList<String>(), Licences.problems(inv))

        val play = Licences.sections(inv, "play").flatMap { it.items }
        assertEquals(
            listOf("Fonts and icons", "Shared core", "Android"),
            Licences.sections(inv, "play").map { it.group },
        )
        val google = listOf(
            "com.google.android.gms:", "com.google.android.libraries.identity.googleid:",
            "androidx.credentials:credentials-play-services-auth", "com.google.firebase:firebase-auth",
        )
        for (prefix in google) assertTrue("play lists $prefix", play.any { it.name.startsWith(prefix) })
        assertTrue(play.any { it.name == "Sign in with Google button and G" })
        assertFalse("no iPhone package on Android", play.any { it.name == "Firebase iOS SDK" })

        val names = play.map { it.name }.toSet()
        assertTrue("OpenSSL is compiled into the Android core", "OpenSSL" in names)
        assertTrue("net.java.dev.jna:jna" in names)
        assertTrue("androidx.compose.ui:ui" in names)
        val protobuf = play.first { it.name == "androidx.datastore:datastore-preferences-external-protobuf" }
        assertEquals("BSD-3-Clause", protobuf.licence)
        assertTrue(Licences.texts(protobuf, inv).joinToString().contains("Copyright 2008 Google Inc."))
        val sqlcipher = play.first { it.name == "SQLCipher" }
        assertEquals("BSD-3-Clause", sqlcipher.licence)
        assertTrue(Licences.texts(sqlcipher, inv).joinToString().contains("Zetetic LLC"))
        val jna = play.first { it.name == "net.java.dev.jna:jna" }
        assertEquals("Apache-2.0", jna.licence)
        val gms = play.first { it.name == "com.google.android.gms:play-services-base" }
        assertEquals("Android Software Development Kit License", gms.licence)
        assertNotNull(gms.notice)
    }

    /** A broken inventory is caught: a missing text, a licence with no
     *  text, an unknown group and an unknown shell each name the component. */
    @Test
    fun problems_name_every_broken_entry() {
        val inv = Licences.parse(
            """
            {"format":1,"groups":["G"],"texts":{"k":"text"},"components":[
              {"group":"G","name":"ok","version":"1","licence":"MIT","shells":["ios"],"texts":["k"]},
              {"group":"G","name":"missing","version":"1","licence":"MIT","shells":["ios"],"texts":["nope"]},
              {"group":"G","name":"bare","version":"1","licence":"MIT","shells":["ios"],"texts":[]},
              {"group":"H","name":"stray","version":"1","licence":"MIT","shells":["ios"],"texts":["k"]},
              {"group":"G","name":"nowhere","version":"1","licence":"MIT","shells":["web"],"texts":["k"]},
              {"group":"G","name":"retired","version":"1","licence":"MIT","shells":["play","direct"],"texts":["k"]},
              {"group":"G","name":"sqlite","version":"1","licence":"Public domain","shells":["ios"],"texts":[]}
            ]}
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "missing: text nope is missing",
                "bare: no licence text for MIT",
                "stray: unknown group H",
                "nowhere: bad shells [\"web\"]",
                "retired: bad shells [\"play\", \"direct\"]",
            ),
            Licences.problems(inv),
        )
    }

    @Test
    fun sections_keep_group_order_and_drop_empty_groups() {
        val inv = Licences.parse(
            """
            {"format":1,"groups":["A","B","C"],"texts":{"k":"t"},"components":[
              {"group":"C","name":"c","version":"1","licence":"MIT","shells":["ios","play"],"texts":["k"]},
              {"group":"B","name":"b","version":"1","licence":"MIT","shells":["play"],"texts":["k"]},
              {"group":"A","name":"a","version":"1","licence":"MIT","shells":["ios"],"texts":["k"]}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf("B", "C"), Licences.sections(inv, "play").map { it.group })
        assertEquals(listOf("b", "c"), Licences.sections(inv, "play").flatMap { it.items }.map { it.name })
        assertEquals(emptyList<LicenceSection>(), Licences.sections(inv, "web"))
        assertEquals("play", Licences.SHELL)
    }

    /** Hard-wrapped licence lines are joined; paragraphs and list items stay
     *  apart. Expected output derived independently (.drafts/d146/reflow_golden.py). */
    @Test
    fun reflow_joins_wrapped_lines_and_keeps_items() {
        val source = "Copyright (c) 2024 A\nAll rights reserved.\n\n\nPermission is hereby\n  granted, free of charge.\n\n" +
            "   * Redistributions must\n     retain the notice.\n   * Neither the name\n\n" +
            "1. Definitions.\n   \"License\" shall mean\n   the terms.\n   (a) You must give\n   a copy.\n"
        assertEquals(
            "Copyright (c) 2024 A All rights reserved.\n\nPermission is hereby granted, free of charge.\n\n" +
                "* Redistributions must retain the notice.\n* Neither the name\n\n" +
                "1. Definitions. \"License\" shall mean the terms.\n(a) You must give a copy.",
            Licences.reflow(source),
        )
        val apache = inventory().texts.getValue("Apache-2.0")
        val words = Regex("\\s+")
        assertEquals(
            "reflow moves line breaks and never a word",
            apache.trim().split(words),
            Licences.reflow(apache).trim().split(words),
        )
    }

    /** D-148: Plex Mono is IBM's own release, byte for byte, and Android's
     *  copies of all three faces are the files iOS bundles. A trimmed copy
     *  may not keep the reserved name "Plex" (SIL OFL 1.1). Hashes read off
     *  IBM's @ibm/plex-mono 2.5.0. */
    @Test
    fun plex_is_ibms_own_files_and_both_shells_ship_the_same_fonts() {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val native = File("../../native/fonts")
        val res = File("src/main/res/font")
        assertEquals("7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43", sha(File(res, "ibmplexmono_regular.ttf")))
        assertEquals("98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c", sha(File(res, "ibmplexmono_medium.ttf")))
        for ((ios, android) in listOf(
            "IBMPlexMono-Regular.ttf" to "ibmplexmono_regular.ttf",
            "IBMPlexMono-Medium.ttf" to "ibmplexmono_medium.ttf",
            "SpaceGrotesk.ttf" to "spacegrotesk.ttf",
        )) {
            assertEquals(ios, sha(File(native, ios)), sha(File(res, android)))
        }
        assertEquals("2.005", inventory().components.first { it.name == "IBM Plex Mono" }.version)
    }

    /** D-215: Plex Sans is IBM's own release too (@ibm/plex-sans 1.1.0, "Version 3.005"), byte for byte: the four
     *  Android copies are the files iOS bundles, their hashes are the ones read off IBM's release, and the JVM's own
     *  font reader parses each as IBM Plex Sans (a corrupt or wrong-family file fails here, not on the phone). */
    @Test
    fun plex_sans_is_ibms_own_files_and_both_shells_ship_the_same_fonts() {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val native = File("../../native/fonts")
        val res = File("src/main/res/font")
        val files = listOf(
            Triple("IBMPlexSans-Regular.ttf", "ibmplexsans_regular.ttf", "975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5"),
            Triple("IBMPlexSans-Medium.ttf", "ibmplexsans_medium.ttf", "331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907"),
            Triple("IBMPlexSans-SemiBold.ttf", "ibmplexsans_semibold.ttf", "a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1"),
            Triple("IBMPlexSans-Bold.ttf", "ibmplexsans_bold.ttf", "9e6c74a889a700d707613d24548fe4ffa6bc59559a0689d2cf9e133bdcdafb2f"),
        )
        for ((ios, android, hash) in files) {
            assertEquals(android, hash, sha(File(res, android)))
            assertEquals(ios, sha(File(native, ios)), sha(File(res, android)))
            val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, File(res, android))
            assertTrue("$android family is ${font.family}", font.family.startsWith("IBM Plex Sans"))
            assertTrue("$android name is ${font.fontName}", font.fontName.startsWith("IBM Plex Sans"))
        }
        // The Licences page lists it, with the OFL text and the reserved name.
        val entry = inventory().components.firstOrNull { it.name == "IBM Plex Sans" }
        assertNotNull("IBM Plex Sans is missing from the Licences page", entry)
        entry!!
        assertEquals("3.005", entry.version)
        assertEquals("OFL-1.1", entry.licence)
        assertTrue(entry.notice.orEmpty().contains("Reserved Font Name \"Plex\""))
    }

    /** D-215: Vardr Runes is Vardr Labs' own font, release v1.000 of vardrlabs/fonts, byte for byte: the Android copy
     *  is the file iOS bundles and both are the release's hash. The Licences page lists it with the OFL and the
     *  reserved name "Vardr". */
    @Test
    fun vardr_runes_is_the_release_file_and_both_shells_ship_it() {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val release = "6d61b17c4929925f5aeb58a4b5541c9524de0cb531ea0c055043b6267b04cf01"
        val ios = File("../../native/fonts/VardrRunes-CarvedRegular.ttf")
        val android = File("src/main/res/font/vardrrunes_carvedregular.ttf")
        assertEquals(release, sha(android))
        assertEquals(release, sha(ios))
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, android)
        assertEquals("Vardr Runes Carved Regular", font.fontName)
        val entry = inventory().components.firstOrNull { it.name == "Vardr Runes" }
        assertNotNull("Vardr Runes is missing from the Licences page", entry)
        entry!!
        assertEquals("1.000", entry.version)
        assertEquals("OFL-1.1", entry.licence)
        assertTrue(entry.notice.orEmpty().contains("Reserved Font Name \"Vardr\""))
        assertTrue("shown on Android", Licences.SHELL in entry.shells)
    }

    /** The About copy is iOS's, word for word: both pin the same hash,
     *  derived independently (.drafts/d146/copy_golden.py; D-196: .drafts/d196/copy_golden.py; D-197: .drafts/d197/copy_golden.py;
     *  D-245, promise 5 after decision 171: .drafts/d245/about_golden.py, the hash iOS pins). */
    @Test
    fun the_about_copy_matches_ios() {
        assertEquals("GUARANTEES.md has seven", 7, AboutInfo.PROMISES.size)
        val joined = (
            AboutInfo.PROMISES + AboutInfo.PRIVACY + listOf(
                AboutInfo.SECURITY_NOTE, AboutInfo.DISCLAIMER, AboutInfo.MAKER, AboutInfo.SECURITY_EMAIL,
            )
            ).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("0efa2d26a8486466afd7ff1a6caed1a46de3dd99f1e9adf2bc9e032fcb088b60", digest)
        assertEquals("Version 0.1.0 (1000)", AboutInfo.versionLine("0.1.0", "1000"))
        assertEquals("Core 0.1.0", AboutInfo.coreLine("0.1.0"))
    }

    @Test
    fun back_steps_from_a_licence_to_the_list_to_about_to_settings() {
        assertEquals("licences", HomeRoutes.parent("licence/12"))
        assertEquals("about", HomeRoutes.parent("licences"))
        assertEquals("account", HomeRoutes.parent("about"))
    }

    /** D-176: fingerprints read the way Play Console and Google Cloud print
     *  them. FIPS 180's "abc" vectors, not values computed by the code. */
    @Test
    fun a_signing_fingerprint_is_colon_separated_upper_hex() {
        assertEquals(
            "A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D",
            SigningCert.fingerprint("abc".toByteArray(), "SHA-1"),
        )
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            SigningCert.fingerprint("abc".toByteArray(), "SHA-256"),
        )
    }

    private fun s225(name: String): String = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** A declaration's text in [file], to its closing brace at column 0. */
    private fun body225(file: String, decl: String): String {
        val text = s225(file)
        val at = text.indexOf(decl)
        assertTrue("$file declares $decl", at >= 0)
        val end = text.indexOf("\n}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    // ---- D-225 Task 5 (Design 6; L71, L73): About, Licences and a licence's page (twins of iOS's Task 4) ----

    /** About's cards and a licence's text panels are glass cards; section labels stand alone (no hairline); the sizes
     *  come from the type scale. The twin of iOS's testAboutUsesGlassCards. */
    @Test
    fun about_uses_glass_cards() {
        val about = s225("About.kt")
        assertTrue("glassCard(dark)" in body225("About.kt", "private fun AboutCard("))
        assertFalse("no panel fill left", "panelShape(" in about)
        assertFalse("no surface fill left", "Tok.surface(dark)" in about)
        assertFalse("no hairline under a label", "Tok.hairline" in body225("About.kt", "private fun AboutSection("))
        val detail = body225("About.kt", "fun LicenceDetailScreen(")
        assertTrue("glassCard(dark, padding = 12)" in detail && "TypeScale.footnote" in detail)
        assertTrue("TypeScale.wordmark" in about && "TypeScale.prose" in about)
        assertFalse("no literal 13, 15 or 32", Regex("""fontSize = (13|15|32)\.sp""").containsMatchIn(about))
    }

    /** Licences: per section a label and one list card of list rows (name over version and licence, chevron); one lazy
     *  item per section. The twin of iOS's testLicencesRowsAreListRows. */
    @Test
    fun licences_rows_are_list_rows() {
        val list = body225("About.kt", "fun LicencesScreen(")
        for (s in listOf("ListCard(dark)", "ListRow(", "trailing = RowTrailing.Chevron", "item(key = \"s/\" + section.group)")) {
            assertTrue("LicencesScreen: $s", s in list)
        }
        assertFalse("LicenceRow is gone", "fun LicenceRow(" in s225("About.kt"))
    }

    /** D-225 fix wave (F1, F2; the paired look: About's first card 152.0 pt on iOS, 133.7 dp here; a licence's lines 12
     *  apart on iOS, 8 here): About's cards space their lines as iOS's `card` VStack does, and a licence's page spaces
     *  its lines as iOS's page VStack does, both read out of About.swift; no card or page adds spacers of its own. */
    @Test
    fun about_and_a_licence_space_their_lines_as_ios() {
        val swift = java.io.File("../../ios/Skipti/App/About.swift").readText()
        val iosCard = Regex("""private func card<Content: View>\(@ViewBuilder _ content: \(\) -> Content\) -> some View \{\s*VStack\(alignment: \.leading, spacing: (\d+)\)""")
            .find(swift)!!.groupValues[1]
        val card = body225("About.kt", "private fun AboutCard(")
        assertTrue("About's card spacing is iOS's ($iosCard)", "spacing: Dp = $iosCard.dp," in card)
        assertTrue("and it spaces its content", "verticalArrangement = Arrangement.spacedBy(spacing)" in card)
        val about = body225("About.kt", "fun AboutScreen(")
        assertFalse("no card adds its own gaps", "Spacer(" in about)
        assertEquals("only Android's own signature card differs", 1, about.split("AboutCard(dark, spacing = ").size - 1)
        val iosDetail = swift.substringAfter("struct LicenceDetailScreen").substringBefore("ForEach(")
        val iosGap = Regex("""VStack\(alignment: \.leading, spacing: (\d+)\)""").find(iosDetail)!!.groupValues[1]
        val detail = body225("About.kt", "fun LicenceDetailScreen(")
        assertTrue("a licence's page spacing is iOS's ($iosGap)", "dark, onBack, spacing = $iosGap.dp," in detail)
        assertFalse("no gaps of its own", "Spacer(" in detail)
    }
}
