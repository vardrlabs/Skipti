package com.vardrlabs.skipti

import java.time.chrono.IsoChronology
import java.util.Locale

/** D-221 fix wave (S10): the JVM has no android.text.format.DateFormat (ICU), so tests that format a date hand
 *  [AppDate] the JDK's CLDR pattern for the same skeleton (`DateTimeFormatterBuilder.getLocalizedDateTimePattern`,
 *  JDK 19+, read by reflection because android.jar does not declare it). The JDK wants the skeleton's letters in
 *  canonical order (y, M, d, h, m), iOS's and Android's templates are in any order, so they are reordered here. */
object JvmDates {
    private val order = "GyQMwEdBhHjCmsvz"

    fun install() {
        val method = java.time.format.DateTimeFormatterBuilder::class.java.getMethod(
            "getLocalizedDateTimePattern", String::class.java, java.time.chrono.Chronology::class.java, Locale::class.java,
        )
        fun pattern(skeleton: String, locale: Locale): String {
            val canonical = skeleton.toList().sortedBy { order.indexOf(it) }.joinToString("")
            return method.invoke(null, canonical, IsoChronology.INSTANCE, locale) as String
        }
        AppDate.bestPattern = { locale, skeleton ->
            // The JDK resolves a date or a time, not both, and not `j` (the locale's hour; both of the app's locales use
            // the 12-hour clock): a stamp is the two joined as CLDR's medium "{date}, {time}".
            val date = skeleton.filter { it in "yMd" }
            val time = if (skeleton.any { it in "jhm" }) "hm" else ""
            listOfNotNull(date.takeIf { it.isNotEmpty() }?.let { pattern(it, locale) }, time.takeIf { it.isNotEmpty() }?.let { pattern(it, locale) })
                .joinToString(", ")
        }
    }
}
