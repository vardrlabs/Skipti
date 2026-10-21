package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-164: the rest of SPEC §3.7 on the JVM; the twin of iOS's PrivacyTests
 *  (iOS cannot block screenshots and hides during recording instead). */
class PrivacyTest {
    @Test
    fun a_root_is_reported_from_any_known_sign() {
        assertFalse(DeviceIntegrity.looksRooted({ false }, "release-keys"))
        assertTrue(DeviceIntegrity.looksRooted({ it == "/data/adb/magisk" }, "release-keys"))
        assertTrue("a test-keys build", DeviceIntegrity.looksRooted({ false }, "test-keys"))
        assertFalse(DeviceIntegrity.looksRooted({ false }, null))
    }

    @Test
    fun the_copy_says_what_the_setting_does() {
        assertEquals("Block screenshots and screen recording", PrivacyCopy.CAPTURE_TOGGLE)
        assertTrue(PrivacyCopy.modified("rooted").startsWith("This phone looks rooted."))
        assertTrue(PrivacyCopy.modified("rooted").endsWith("this is a warning, not a block."))
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

    /** D-225 (Design 3; L-D217-9): the screen-privacy switch is a list row with the drawn switch, so its title keeps a
     *  row gap from the switch and its honest limit stays beside it (unlimited lines); no chips and no Material switch
     *  are left in AppLock.kt. The rooted warning is its own glass card in `watch`. */
    @Test
    fun security_switch_has_room() {
        val row = body225("AppLock.kt", "fun PrivacyRow(")
        for (s in listOf(
            "ListRow(", "PrivacyCopy.CAPTURE_TOGGLE", "parts = listOf(PrivacyCopy.CAPTURE_DETAIL)",
            "RowTrailing.Toggle(ScreenPrivacy.enabled)", "ScreenPrivacy.set(context, it)", "summaryLines = 0",
        )) assertTrue("PrivacyRow: $s", s in row)
        val t = s225("AppLock.kt")
        assertFalse("FilterChip(" in t)
        assertFalse("a Material switch", Regex("""\bSwitch\(""").containsMatchIn(t))
        assertFalse("switchColors(" in t)
        assertFalse("PrivacySettings is gone", "fun PrivacySettings(" in t)
        val warning = body225("AppLock.kt", "fun ModifiedPhoneWarning(")
        assertTrue("Tok.watch(dark)" in warning && "glassCard(dark)" in warning && "DeviceIntegrity.looksRooted()" in warning)
    }
}
