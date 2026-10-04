package im.client

import java.util.TimeZone

/** Android 也是 JVM */
actual fun localOffsetMs(atEpochMs: Long): Long =
    TimeZone.getDefault().getOffset(atEpochMs).toLong()
