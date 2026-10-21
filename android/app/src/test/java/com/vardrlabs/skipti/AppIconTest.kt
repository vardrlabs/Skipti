package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * D-216 (Design 1, 2 and 6): the three launcher entries, their adaptive icons and layers, the previews the picker
 * draws (the same bytes as iOS's), and the notification glyph. The twin of iOS's AppIconTests (the icon sets).
 */
class AppIconTest {

    private val manifest: org.w3c.dom.Document by lazy {
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(java.io.File("src/main/AndroidManifest.xml"))
    }

    private fun elements(parent: org.w3c.dom.Node, tag: String): List<Element> {
        val list = if (parent is Element) parent.getElementsByTagName(tag) else (parent as org.w3c.dom.Document).getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    /** A component's full name: a leading dot is the namespace's (`com.vardrlabs.skipti`, build.gradle.kts). */
    private fun Element.component(): String = android("name").let { if (it.startsWith(".")) "com.vardrlabs.skipti$it" else it }

    private fun Element.isLauncher(): Boolean = elements(this, "intent-filter").any { filter ->
        elements(filter, "action").any { it.android("name") == "android.intent.action.MAIN" } &&
            elements(filter, "category").any { it.android("name") == "android.intent.category.LAUNCHER" }
    }

    /** Exactly three aliases, one per icon, each opening MainActivity; only Aurora's is enabled in the manifest. */
    @Test
    fun the_launcher_entries_are_three_aliases() {
        val aliases = elements(manifest, "activity-alias")
        val names = listOf("com.vardrlabs.skipti.IconAurora", "com.vardrlabs.skipti.IconNorse", "com.vardrlabs.skipti.IconVardr")
        assertEquals("the permanent names (CLAUDE.md section 10)", names, aliases.map { it.component() })
        assertEquals("the names AppIcon carries", AppIcon.entries.map { it.alias }, aliases.map { it.component() })
        val icons = listOf("@mipmap/ic_launcher", "@mipmap/ic_launcher_norse", "@mipmap/ic_launcher_vardr")
        for ((i, alias) in aliases.withIndex()) {
            val name = alias.component()
            assertEquals(name, ".MainActivity", alias.android("targetActivity"))
            assertEquals(name, "true", alias.android("exported"))
            assertEquals(name, "Skipti", alias.android("label"))
            assertEquals(name, icons[i], alias.android("icon"))
            assertEquals(name, icons[i], alias.android("roundIcon"))
            assertTrue("$name is a launcher entry", alias.isLauncher())
            val enabled = alias.android("enabled")
            assertEquals("$name states its default", true, enabled == "true" || enabled == "false")
            assertEquals("$name enabled by default", AppIcon.entries[i].enabledByDefault, enabled == "true")
        }
        assertEquals("Aurora alone is on", listOf("true", "false", "false"), aliases.map { it.android("enabled") })
        // Nothing else in the manifest is a launcher entry.
        assertEquals(3, (elements(manifest, "activity") + aliases).count { it.isLauncher() })
    }

    /** Design 1 (the whole-branch review's M2): Aurora is also the application's own icon, the one system Settings (and
     *  Recents, for a task MainActivity started) shows. */
    @Test
    fun the_application_icon_is_aurora() {
        val application = elements(manifest, "application").single()
        assertEquals("@mipmap/ic_launcher", application.android("icon"))
        assertEquals("@mipmap/ic_launcher_round", application.android("roundIcon"))
        assertEquals("Aurora's alias draws the same icon", application.android("icon"), elements(manifest, "activity-alias").first().android("icon"))
    }

    /** Task 2's review (2): what the switcher reads as an alias's DEFAULT state ([AppIconPlan.enabled]) is what the
     *  manifest's `android:enabled` says, alias by alias, so the plan and the manifest cannot drift apart. */
    @Test
    fun the_plans_defaults_are_the_manifests() {
        val enabled = elements(manifest, "activity-alias").associate { it.component() to it.android("enabled") }
        assertEquals("every icon has its alias", AppIcon.entries.map { it.alias }.toSet(), enabled.keys)
        val default = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val off = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val on = enabled.filterValues { it == "true" }.keys
        assertEquals("the manifest enables one", 1, on.size)
        assertEquals("nothing written yet: the manifest's one", listOf(on.single()), AppIconPlan.enabled(AppIcon.entries.associate { it.alias to default }).map { it.alias })
        for (icon in AppIcon.entries) {
            // This alias left at DEFAULT, the other two written off: it is on exactly when the manifest enables it.
            val states = AppIcon.entries.associate { it.alias to if (it == icon) default else off }
            val expected = if (enabled.getValue(icon.alias) == "true") listOf(icon) else emptyList()
            assertEquals("${icon.alias} at DEFAULT", expected, AppIconPlan.enabled(states))
        }
    }

    /** MainActivity is opened by the aliases, so it is no launcher entry itself; the invite link still opens it. */
    @Test
    fun main_activity_is_not_a_launcher_but_keeps_the_invite_link() {
        val main = elements(manifest, "activity").single { it.android("name") == ".MainActivity" }
        assertFalse("no LAUNCHER on MainActivity", main.isLauncher())
        assertFalse(
            "no LAUNCHER category anywhere on MainActivity",
            elements(main, "category").any { it.android("name") == "android.intent.category.LAUNCHER" },
        )
        val filters = elements(main, "intent-filter")
        assertEquals("one filter: the invite link", 1, filters.size)
        val view = filters.single()
        assertEquals("true", view.android("autoVerify"))
        assertEquals(listOf("android.intent.action.VIEW"), elements(view, "action").map { it.android("name") })
        assertEquals(
            setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            elements(view, "category").map { it.android("name") }.toSet(),
        )
        val data = elements(view, "data").single()
        assertEquals(listOf("https", "skipti.vardrlabs.com", "/join"), listOf(data.android("scheme"), data.android("host"), data.android("path")))
    }

    /** Each launcher icon is adaptive: its theme's background and foreground, and the one shared monochrome layer
     *  (Android 13+ themed icons). */
    @Test
    fun each_icon_is_adaptive_with_the_shared_monochrome() {
        val icons = mapOf(
            "ic_launcher" to "ic_launcher",
            "ic_launcher_round" to "ic_launcher",
            "ic_launcher_norse" to "ic_launcher_norse",
            "ic_launcher_vardr" to "ic_launcher_vardr",
        )
        for ((file, layers) in icons) {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(java.io.File("src/main/res/mipmap-anydpi-v26/$file.xml"))
            val root = doc.documentElement
            assertEquals(file, "adaptive-icon", root.tagName)
            fun layer(tag: String): String {
                val found = elements(root, tag)
                assertEquals("$file has one <$tag>", 1, found.size)
                return found.single().android("drawable")
            }
            assertEquals(file, "@mipmap/${layers}_background", layer("background"))
            assertEquals(file, "@mipmap/${layers}_foreground", layer("foreground"))
            assertEquals(file, "@mipmap/ic_launcher_monochrome", layer("monochrome"))
        }
    }

    /** Every layer at every density, at the size its density takes (108 dp at 1, 1.5, 2, 3 and 4 px per dp). */
    @Test
    fun every_layer_exists_at_every_density() {
        val sizes = mapOf("mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432)
        val layers = listOf(
            "ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome",
            "ic_launcher_norse_background", "ic_launcher_norse_foreground",
            "ic_launcher_vardr_background", "ic_launcher_vardr_foreground",
        )
        for ((density, px) in sizes) {
            val folder = java.io.File("src/main/res/mipmap-$density")
            assertEquals(
                "mipmap-$density holds exactly the layers",
                layers.map { "$it.png" }.toSet(),
                folder.listFiles()!!.map { it.name }.toSet(),
            )
            for (layer in layers) {
                val (w, h) = pngSize(java.io.File(folder, "$layer.png"))
                assertEquals("$density/$layer width", px, w)
                assertEquals("$density/$layer height", px, h)
            }
        }
    }

    /** The picker's previews are byte for byte iOS's (one image, written once by brand/make_icon_resources.sh). */
    @Test
    fun the_previews_are_the_ios_previews() {
        val names = mapOf("aurora" to "Aurora", "norse" to "Norse", "vardr" to "Vardr")
        assertEquals(AppIcon.entries.map { it.stored }.toSet(), names.keys)
        for ((android, ios) in names) {
            val mine = java.io.File("src/main/res/drawable-nodpi/icon_preview_$android.png")
            val theirs = java.io.File("../../ios/Skipti/App/Assets.xcassets/IconPreview-$ios.imageset/icon-preview-$android.png")
            assertEquals("$android preview", sha256(theirs), sha256(mine))
            assertEquals("$android preview is 144 px", 144 to 144, pngSize(mine))
        }
    }

    /** D-216 (Design 6): the push notifications' small icon is the cut S, two white filled paths, and the bell is gone. */
    @Test
    fun the_notification_glyph_is_the_cut_s() {
        val push = java.io.File("src/main/java/com/vardrlabs/skipti/Push.kt").readText()
        assertTrue("Push.kt draws the cut S", ".setSmallIcon(R.drawable.ic_stat_skipti)" in push)
        assertFalse("no bell left in Push.kt", "ic_stat_bell" in push)
        val meta = elements(manifest, "meta-data")
            .single { it.android("name") == "com.google.firebase.messaging.default_notification_icon" }
        assertEquals("@drawable/ic_stat_skipti", meta.android("resource"))
        assertFalse("the bell's drawing is gone", java.io.File("src/main/res/drawable/ic_stat_bell.xml").exists())
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(java.io.File("src/main/res/drawable/ic_stat_skipti.xml"))
        val vector = doc.documentElement
        assertEquals("vector", vector.tagName)
        assertEquals("24dp", vector.android("width"))
        assertEquals("24dp", vector.android("height"))
        assertEquals("24", vector.android("viewportWidth"))
        assertEquals("24", vector.android("viewportHeight"))
        val paths = elements(vector, "path")
        assertEquals("the two halves of the cut S", 2, paths.size)
        for (path in paths) {
            assertEquals("#FFFFFFFF", path.android("fillColor"))
            assertEquals("no stroke", "", path.android("strokeColor"))
            assertTrue("a closed outline", path.android("pathData").let { it.startsWith("M") && it.endsWith("Z") })
        }
        // The committed brand asset is the same file the app ships.
        assertEquals(
            sha256(java.io.File("../../brand/skipti-icon-assets/ic_stat_skipti.xml")),
            sha256(java.io.File("src/main/res/drawable/ic_stat_skipti.xml")),
        )
    }

    /** A PNG's width and height, read from its IHDR chunk (bytes 16 to 23, big-endian). */
    private fun pngSize(file: java.io.File): Pair<Int, Int> {
        val bytes = file.readBytes()
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("${file.name} is a PNG", bytes.size > 24 && bytes.copyOfRange(0, 8).contentEquals(signature))
        assertEquals("${file.name} starts with IHDR", "IHDR", String(bytes.copyOfRange(12, 16), Charsets.US_ASCII))
        fun int(at: Int) = java.nio.ByteBuffer.wrap(bytes, at, 4).int
        return int(16) to int(20)
    }

    private fun sha256(file: java.io.File): String {
        assertTrue("${file.path} exists", file.exists())
        return java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
