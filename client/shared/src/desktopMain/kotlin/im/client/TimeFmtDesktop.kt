package im.client

import java.util.TimeZone

/** JVM：按目标时刻取偏移，能正确带上夏令时 */
actual fun localOffsetMs(atEpochMs: Long): Long =
    TimeZone.getDefault().getOffset(atEpochMs).toLong()
