package cx.m42.apps.dates

import java.util.TimeZone

internal actual fun localUtcOffsetMillis(epochMillis: Long): Long =
    TimeZone.getDefault().getOffset(epochMillis).toLong()
