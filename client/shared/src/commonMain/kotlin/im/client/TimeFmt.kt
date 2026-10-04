package im.client

// 时间显示：原来直接把毫秒时间戳打在界面上（#1774000000000），很难看。
// 这里做成本地时区的 HH:mm / MM-DD HH:mm，纯整数运算，不引第三方依赖。

/** 当前时区相对 UTC 的偏移（毫秒）。桌面/Android 用 JVM TimeZone，Web 用 JS Date */
expect fun localOffsetMs(atEpochMs: Long): Long

/** 会话/搜索里统一用这个格式化 */
fun formatChatTime(atEpochMs: Long): String {
    if (atEpochMs <= 0) return ""
    val local = atEpochMs + localOffsetMs(atEpochMs)
    val dayMs = 86_400_000L
    val dayIndex = floorDiv(local, dayMs)
    val millisOfDay = local - dayIndex * dayMs
    val hour = (millisOfDay / 3_600_000L).toInt()
    val minute = ((millisOfDay % 3_600_000L) / 60_000L).toInt()
    val clock = pad2(hour) + ":" + pad2(minute)

    val today = floorDiv(atEpochMs + localOffsetMs(atEpochMs), dayMs)
    return if (dayIndex == today) clock else monthDay(dayIndex) + " " + clock
}

/** 只要日期部分（MM-DD） */
fun formatChatDay(atEpochMs: Long): String {
    if (atEpochMs <= 0) return ""
    val dayIndex = floorDiv(atEpochMs + localOffsetMs(atEpochMs), 86_400_000L)
    return monthDay(dayIndex)
}

private fun pad2(v: Int) = if (v < 10) "0$v" else "$v"

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a < 0) != (b < 0)) q - 1 else q
}

/**
 * 纪元天数 → MM-DD。
 * 用 Howard Hinnant 的 civil-from-days，纯整数，common 代码里没有日期库时最省事。
 */
private fun monthDay(z0: Long): String {
    val z = z0 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return pad2(m.toInt()) + "-" + pad2(d.toInt())
}
