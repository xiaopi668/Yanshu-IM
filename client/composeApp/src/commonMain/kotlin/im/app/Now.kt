package im.app

/** 当前时间（epoch 毫秒）。common 层没有墙钟，各端自己给。 */
expect fun nowEpochMillis(): Long
