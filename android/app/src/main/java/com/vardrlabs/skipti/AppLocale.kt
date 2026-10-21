package com.vardrlabs.skipti

import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.util.Locale
import uniffi.skipti_core.LocaleTag

/**
 * Locale is an axis SEPARATE from currency (D-50): en-IN formatting gives
 * lakh/crore grouping (₹12,34,567.89); anything else formats western.
 * Currency comes from the RECORD, never from the device: en-US formatting
 * of INR and en-IN formatting of USD are both legitimate. Mirrors
 * ios/Skipti/App/AppLocale.swift.
 */
object AppLocale {
    fun tag(locale: Locale = Locale.getDefault()): LocaleTag =
        if (locale.language == "en" && locale.country == "IN") LocaleTag.EN_IN else LocaleTag.EN_US

    /** The tag every money-formatting call site uses. */
    val current: LocaleTag
        get() = tag()
}

/** D-228: what a screen shows where there is no value (a money call core refused, the Overview "Settled" tile).
 *  SKIPTI's own typed hyphen, one constant for every site; the twin of iOS's `Placeholder.none`. */
object Placeholder {
    const val NONE = "-"

    /** D-228 decision 43: how TalkBack says [NONE] where it is shown as a value (never "hyphen" or "minus"); the
     *  twin of iOS's `Placeholder.spokenNone`. */
    const val SPOKEN_NONE = "none"

    /** The spoken form of a shown value: [SPOKEN_NONE] for the placeholder, else the value itself. */
    fun spoken(shown: String): String = if (shown == NONE) SPOKEN_NONE else shown
}

/** D-221 fix wave (S10): every date the app shows, formatted in ONE place from the locale's own skeleton, the twin of
 *  iOS's `AppDate` (`setLocalizedDateFormatFromTemplate`): "dMMMy" reads "Oct 15, 2025" in en-US and "15 Oct 2025" in
 *  en-IN, as on the iPhone; no fixed pattern (the old "d MMM y" put the day first in en-US too). The locale axis is
 *  AppLocale's. Dates are presentation and format in the shell (D-54); money never does. */
object AppDate {
    /** The best pattern for a skeleton in a locale: the platform's (ICU, through android.text.format.DateFormat). JVM
     *  tests, which have no ICU, hand in the JDK's CLDR one (JvmDates); nothing else sets it. */
    @Volatile
    var bestPattern: (Locale, String) -> String = { locale, skeleton ->
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    }

    fun locale(tag: LocaleTag): Locale = Locale.forLanguageTag(if (tag == LocaleTag.EN_IN) "en-IN" else "en-US")

    /** Day, month, year: "Sep 16, 2026" / "16 Sept 2026". */
    fun day(ms: Long, tag: LocaleTag = AppLocale.current, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, "dMMMy", tag, zone)

    /** Day, month, year and time, for section 5.7's "added by" and "last edited by" stamps. D-225 T7 (A4, from D-223's
     *  look: iOS read "Oct 17, 2026 at 6:01 AM", Android "Oct 17, 2026, 5:53 AM"): ONE wording on both phones, the
     *  patterns iOS's own `dMMMyjmm` template resolves to (read off Foundation: en_US "MMM d, y 'at' h:mm a", en_IN
     *  "d MMM y 'at' h:mm a", a narrow no-break space before the marker), written out here so neither ICU's version
     *  moves them: English month names and "AM" / "PM" from Locale.US on both tags (iOS's en_IN reads "Sep" and "AM",
     *  where a JDK's en-IN would say "Sept" and "am"). D-225 fix wave (I2, A10, WORKLOG decision 25): the time follows
     *  the phone's own 12 or 24-hour setting ([hours24], the caller's `DateFormat.is24HourFormat`; kept a parameter so
     *  the stamp stays pure), and the Gregorian calendar is pinned, as iOS's formatter pins it. The four patterns are
     *  iOS's `AppDate.stampUS` / `stampIN` / `stampUS24` / `stampIN24`. */
    fun stamp(ms: Long, hours24: Boolean, tag: LocaleTag = AppLocale.current, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(stampPattern(tag, hours24), Locale.US)
            .withChronology(IsoChronology.INSTANCE)
            .format(Instant.ofEpochMilli(ms).atZone(zone))

    fun stampPattern(tag: LocaleTag, hours24: Boolean): String = when {
        tag == LocaleTag.EN_IN && hours24 -> STAMP_IN24
        tag == LocaleTag.EN_IN -> STAMP_IN
        hours24 -> STAMP_US24
        else -> STAMP_US
    }

    const val STAMP_US = "MMM d, y 'at' h:mm\u202Fa"
    const val STAMP_IN = "d MMM y 'at' h:mm\u202Fa"
    const val STAMP_US24 = "MMM d, y 'at' HH:mm"
    const val STAMP_IN24 = "d MMM y 'at' HH:mm"

    /** D-223: day and month, for a change a week old or more (RelativeWhen.Date): "Oct 16" / "16 Oct", from the CLDR
     *  "dMMM" skeleton on the app's one date axis, AppLocale's en-US / en-IN like [day] (WORKLOG decision 19; iOS's twin
     *  is `RelativeWhen.dayMonth`). */
    fun dayMonth(ms: Long, tag: LocaleTag = AppLocale.current, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, "dMMM", tag, zone)

    private fun format(ms: Long, skeleton: String, tag: LocaleTag, zone: ZoneId): String {
        val l = locale(tag)
        return DateTimeFormatter.ofPattern(bestPattern(l, skeleton), l).format(Instant.ofEpochMilli(ms).atZone(zone))
    }
}
