package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-212: a Dialog, sheet or menu opens a Compose window of its own, which
 * resets LocalDensity and LocalConfiguration to the phone's, so the root's
 * text size and night bit stop at its edge. Every such window draws its
 * content inside SkiptiWindowLocals, and every AlertDialog goes through the
 * one SkiptiAlertDialog wrapper (Appearance.kt) that does so for each slot.
 */
class WindowLocalsTest {
    private val sources = java.io.File("src/main/java/com/vardrlabs/skipti")
        .listFiles { f -> f.name.endsWith(".kt") }!!.sortedBy { it.name }

    /** The index of the bracket closing the one at [open]. Braces inside
     *  string templates are balanced, so a plain count is enough here. */
    private fun close(src: String, open: Int): Int {
        val pair = mapOf('(' to ')', '{' to '}')
        val opener = src[open]
        val closer = pair.getValue(opener)
        var depth = 0
        for (i in open until src.length) {
            if (src[i] == opener) depth++
            if (src[i] == closer) {
                depth--
                if (depth == 0) return i
            }
        }
        error("no close for ${src[open]} at $open")
    }

    @Test
    fun only_the_wrapper_calls_alert_dialog() {
        val call = Regex("""(?<![A-Za-z0-9_])AlertDialog\(""")
        for (f in sources) {
            val n = call.findAll(f.readText()).count()
            assertEquals("${f.name} calls AlertDialog( directly; use SkiptiAlertDialog", if (f.name == "Appearance.kt") 1 else 0, n)
        }
        val wrappers = sources.sumOf { Regex("""SkiptiAlertDialog\(""").findAll(it.readText()).count() }
        assertTrue("the app's dialogs go through the wrapper ($wrappers)", wrappers >= 18)
    }

    /** The wrapper's own body: every slot lambda it hands Material is wrapped, one SkiptiWindowLocals per slot.
     *  The slots are the composable lambda parameters in its signature, so a new slot added without a wrapper
     *  fails here instead of drawing at the phone's text size. */
    @Test
    fun the_alert_wrapper_wraps_every_slot() {
        val src = sources.single { it.name == "Appearance.kt" }.readText()
        val at = src.indexOf("fun SkiptiAlertDialog(")
        assertTrue("SkiptiAlertDialog is declared in Appearance.kt", at >= 0)
        val sigOpen = src.indexOf('(', at)
        val sigEnd = close(src, sigOpen)
        val slots = Regex("""@Composable\s*\(\)\s*->\s*Unit""").findAll(src.substring(sigOpen, sigEnd)).count()
        assertEquals("the wrapper's composable slots (confirm, dismiss, title, text)", 4, slots)
        val bodyOpen = src.indexOf('{', sigEnd)
        val body = src.substring(bodyOpen, close(src, bodyOpen))
        val wraps = Regex("""SkiptiWindowLocals\(""").findAll(body).count()
        assertEquals("each of the $slots slots is wrapped in SkiptiWindowLocals", slots, wraps)
    }

    /** Strict form: the content lambda of every window call STARTS with
     *  `SkiptiWindowLocals {`, and that block runs to the lambda's end, so
     *  nothing in the window draws outside it. */
    @Test
    fun every_window_draws_inside_skipti_window_locals() {
        val call = Regex("""(?<![A-Za-z0-9_])(Dialog|ModalBottomSheet|DropdownMenu|ExposedDropdownMenu|Popup)\(""")
        var seen = 0
        for (f in sources) {
            val src = f.readText()
            for (m in call.findAll(src)) {
                // A declaration (`fun Dialog(`) is not a call.
                if (src.substring(maxOf(0, m.range.first - 4), m.range.first).endsWith("fun ")) continue
                seen++
                val args = m.range.last
                val argsEnd = close(src, args)
                var at = argsEnd + 1
                while (at < src.length && src[at].isWhitespace()) at++
                val where = "${f.name}:${src.substring(0, m.range.first).count { it == '\n' } + 1} ${m.groupValues[1]}"
                assertTrue("$where has no trailing content lambda", at < src.length && src[at] == '{')
                val bodyEnd = close(src, at)
                val body = src.substring(at + 1, bodyEnd)
                val lead = body.indexOfFirst { !it.isWhitespace() }
                assertTrue("$where: its content does not start with SkiptiWindowLocals {", lead >= 0 && body.startsWith("SkiptiWindowLocals {", lead))
                val inner = at + 1 + lead + "SkiptiWindowLocals ".length
                val innerEnd = close(src, inner)
                assertTrue("$where: content after the SkiptiWindowLocals block", src.substring(innerEnd + 1, bodyEnd).isBlank())
            }
        }
        // D-225 T7: Add expense's dropdown went (its choices are choice pages, drawn in PushedWindow's Dialog).
        assertTrue("the check found the app's windows ($seen)", seen >= 4)
    }
}
