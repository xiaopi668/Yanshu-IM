package im.app

@JsFun("() => Date.now()")
private external fun jsNow(): Double

actual fun nowEpochMillis(): Long = jsNow().toLong()
