package com.vardrlabs.skipti

import androidx.compose.runtime.saveable.SaverScope
import kotlin.reflect.KClass
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The saver's blind spot, closed exhaustively (D-113). `make android-test`
 * proves Kotlin logic against real core; it does not execute Compose state
 * restoration, and the save-state JSON saver's polymorphic scope needs
 * `@Serializable` on EVERY sealed subclass. When five of seven lacked it,
 * all 53 JVM tests passed while the app crashed on first launch: the only
 * witness was an emulator run someone remembered to do.
 *
 * So this test walks `SplitDraft::class.sealedSubclasses` BY REFLECTION:
 * a hand-listed set of seven carries the identical "forgot to add the new
 * one" failure it exists to prevent. An eighth subclass added without
 * `@Serializable` reddens this test without anyone editing it: the sweep
 * constructs it, the saver's own Json fails to encode it, and the round
 * trip never happens.
 */
class SaverRoundTripTest {

    /** The SAME Json configuration the saver uses (AddExpenseScreen.kt). */
    private val saverJson = Json

    private fun representative(subclass: KClass<out AddExpenseUi.SplitDraft>): AddExpenseUi.SplitDraft {
        subclass.objectInstance?.let { return it }
        // Data classes with ALL-default parameters carry a synthetic no-arg
        // constructor, so java reflection builds the representative without
        // kotlin-reflect, no new dependency for the sweep.
        // All five data variants carry all-default parameters, so the
        // compiler-generated synthetic no-arg constructor builds a
        // legitimate representative. (KCallable.callBy refuses to resolve
        // on this toolchain even with kotlin-reflect present, measured;
        // java reflection is the honest route, kotlin-reflect stays for
        // sealedSubclasses at runtime.)
        return try {
            val noArg = subclass.java.getDeclaredConstructor()
            noArg.isAccessible = true
            noArg.newInstance() as AddExpenseUi.SplitDraft
        } catch (e: NoSuchMethodException) {
            fail("${subclass.simpleName} has no no-arg constructor to build")
            throw IllegalStateException()
        } catch (e: Exception) {
            fail("${subclass.simpleName} cannot be constructed with defaults: $e")
            throw IllegalStateException()
        }
    }

    @Test
    fun every_sealed_subclass_round_trips_through_the_savers_json() {
        val subclasses = AddExpenseUi.SplitDraft::class.sealedSubclasses
        assertTrue(
            "the sweep must actually cover the hierarchy (found ${subclasses.size})",
            subclasses.size >= 7,
        )
        val seen = mutableSetOf<String>()
        for (subclass in subclasses) {
            val instance = representative(subclass)
            val json = try {
                saverJson.encodeToString(AddExpenseUi.SplitDraft.serializer(), instance)
            } catch (e: SerializationException) {
                fail(
                    "${subclass.simpleName} is missing @Serializable (or is not in the " +
                        "saver's polymorphic scope): the app would crash on first state " +
                        "save. Add the annotation. ($e)",
                )
                throw IllegalStateException()
            }
            val restored = saverJson.decodeFromString(
                AddExpenseUi.SplitDraft.serializer(),
                json,
            )
            assertEquals(
                "${subclass.simpleName} must round-trip byte-equal through the saver's Json",
                instance,
                restored,
            )
            seen += subclass.simpleName!!
        }
        // Named for the record: the sweep covers the whole known hierarchy.
        assertEquals(
            setOf("Equal", "Exact", "Percentage", "Shares", "Adjustment", "Itemised", "PayerOnly"),
            seen,
        )
    }

    @Test
    fun the_saver_rejects_what_the_app_never_created() {
        // The negative face: a decode of an unknown subtype must fail loudly
        // rather than silently degrade: the same property that makes the
        // missing-annotation crash LOUD instead of a silent data loss.
        try {
            saverJson.decodeFromString(
                AddExpenseUi.SplitDraft.serializer(),
                """{"type":"SplitKindOfTheFuture","label":"x"}""",
            )
            fail("an unknown subtype must not decode")
        } catch (_: SerializationException) {
            // the polymorphic scope refusing what it does not know
        }
    }

    /** C2: the sign-in confirmation and the required username step are kept
     *  across recreation by SignInMethod's saver: every method comes back as
     *  itself, and nothing the app never stored comes back at all. */
    @Test
    fun a_sign_in_method_round_trips_through_its_saver() {
        val scope = SaverScope { true }
        val saver = SignInMethod.saver
        for (method in listOf(SignInMethod.Apple, SignInMethod.Google, SignInMethod.Email("sam@example.org"))) {
            val saved = with(saver) { scope.save(method) }
            assertEquals(method.encoded, saved)
            assertEquals(method, saver.restore(saved!!))
        }
        assertNull("no sign-in saves nothing", with(saver) { scope.save(null) })
        assertNull(saver.restore("passkey"))
        assertNull(saver.restore("email:"))
    }
}
