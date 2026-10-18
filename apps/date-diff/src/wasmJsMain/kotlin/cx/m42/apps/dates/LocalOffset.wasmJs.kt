package cx.m42.apps.dates

// The browser's zone, as JavaScript's Date reports it: minutes *behind* UTC, hence the sign.
private fun offsetMinutes(epochMillis: Double): Int = js("-new Date(epochMillis).getTimezoneOffset()")

internal actual fun localUtcOffsetMillis(epochMillis: Long): Long =
    offsetMinutes(epochMillis.toDouble()) * 60_000L
